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

/** The LMC hybrid as a section of its own: keys, switch independent of SCAM HDR, per-shot profile, migration. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class, qualifiers="w400dp-h880dp-mdpi")
public class HybridSettingsTest {
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
    @After public void tearDown(){PreferenceKeys.endShotProfile();if(camera!=null)camera.close();}

    private PreferenceScreen inflate(){
        SettingsMigration.prepare(context,prefs);
        PreferenceManager pm=new PreferenceManager(context);
        PreferenceScreen screen=pm.inflateFromResource(context,R.xml.preferences,null);pm.setPreferences(screen);
        return screen;
    }
    private static void collect(PreferenceGroup group,List<Preference> out){
        for(int i=0;i<group.getPreferenceCount();i++){
            Preference p=group.getPreference(i);out.add(p);
            if(p instanceof PreferenceGroup)collect((PreferenceGroup)p,out);
        }
    }

    @Test public void hybridSectionOwnsAllHybridKeysAndScamHdrHasNone() {
        PreferenceScreen root=inflate();
        PreferenceScreen hybrid=root.findPreference("lmc_hybrid_screen");
        assertNotNull(hybrid);assertEquals("Hybrid",hybrid.getTitle().toString());
        // The concept tree (P6 / P6b): Кадры и захват / Модель шума / Склейка / Шумоподавление / Обработка ArkCore (+ Диагностика).
        // ArkCore is shared with SCAM HDR, which links to the same page.
        PreferenceScreen ark=root.findPreference("lmc_hybrid_arkcore_screen");
        assertNotNull(ark);assertTrue(hybrid.findPreference("lmc_hybrid_arkcore_screen") instanceof PreferenceScreen);
        assertNotNull(root.findPreference("vivo_hdr_ark_link"));
        for(String screen:new String[]{"lmc_hybrid_capture_screen","lmc_hybrid_noise_screen","lmc_hybrid_merge_screen","lmc_hybrid_photo_screen",
                "lmc_hybrid_nr_snr_screen","lmc_hybrid_nr_mult_screen","lmc_hybrid_nr_safe_screen","lmc_hybrid_rejection_screen"})
            assertTrue(screen,hybrid.findPreference(screen) instanceof PreferenceScreen);
        for(String screen:new String[]{"lmc_hybrid_ark_sharp_screen","lmc_hybrid_ark_rl_screen","lmc_hybrid_ark_tone_screen","lmc_hybrid_ark_vibrance_screen"})
            assertTrue(screen,ark.findPreference(screen) instanceof PreferenceScreen);
        for(String category:new String[]{"lmc_hybrid_capture_category","lmc_hybrid_shasta_category","lmc_hybrid_boost_category",
                "lmc_hybrid_zipper_category","lmc_hybrid_weights_category","lmc_hybrid_diag_category"})
            assertTrue(category,hybrid.findPreference(category) instanceof PreferenceCategory);
        for(String category:new String[]{"lmc_hybrid_sharp_category","lmc_hybrid_ark_artifacts_category"})
            assertTrue(category,ark.findPreference(category) instanceof PreferenceCategory);
        assertNull(root.findPreference("pref_lmc_hybrid_enabled"));
        assertTrue(root.findPreference(PreferenceKeys.ROUTE_KEY) instanceof androidx.preference.ListPreference);
        List<Preference> inside=new ArrayList<>();collect(hybrid,inside);collect(ark,inside);
        Set<String> insideKeys=new HashSet<>();
        for(Preference p:inside){
            String key=p.getKey();assertNotNull(key);insideKeys.add(key);
            if(p instanceof PreferenceGroup)continue;
            assertTrue(key,key.startsWith(PreferenceKeys.HYBRID_PREFIX));
            assertTrue(key,ModuleProfiles.isLocal(key));
            if(p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.UniversalSeekBarPreference){
                assertNotNull(key+" needs numeric bounds",SettingsNumericRules.bounds(key));
            }
        }
        assertTrue(insideKeys.size()>45);
        List<Preference> all=new ArrayList<>();collect(root,all);
        for(Preference p:all){
            String key=p.getKey();if(key==null)continue;
            assertFalse(key,key.startsWith("pref_vivo_nice_hybrid"));
            assertFalse(key,key.equals("pref_vivo_nice_engine"));
            if(key.startsWith(PreferenceKeys.HYBRID_PREFIX))assertTrue(key+" outside the hybrid / ArkCore sections",insideKeys.contains(key));
        }
    }

    /** Every nested screen opens as its own fragment: a dependency on a key of another screen crashes on opening it. */
    @Test public void dependenciesStayInsideTheirScreen() {
        PreferenceScreen root=inflate();
        List<Preference> all=new ArrayList<>();collect(root,all);
        int checked=0;
        for(Preference p:all){
            String dependency=p.getDependency();
            if(dependency==null)continue;
            PreferenceGroup screen=p.getParent();
            while(screen!=null&&!(screen instanceof PreferenceScreen))screen=screen.getParent();
            assertNotNull(p.getKey(),screen);
            assertNotNull(p.getKey()+" depends on "+dependency+" outside its screen "+screen.getKey(),screen.findPreference(dependency));
            checked++;
        }
        assertTrue(checked>10);
    }

    @Test public void listSummariesFormatWithTheirEntries() {
        PreferenceScreen hybrid=inflate().findPreference("lmc_hybrid_screen");
        List<Preference> inside=new ArrayList<>();collect(hybrid,inside);
        int lists=0;
        for(Preference p:inside) if(p instanceof ListPreference){
            ListPreference l=(ListPreference)p;lists++;
            assertEquals(p.getKey(),l.getEntries().length,l.getEntryValues().length);
            l.setValueIndex(0);
            // ListPreference summaries go through String.format: an unescaped % throws here.
            CharSequence summary=l.getSummary();
            assertNotNull(p.getKey(),summary);
            assertTrue(p.getKey()+": "+summary,summary.toString().startsWith(l.getEntries()[0].toString()));
        }
        assertTrue(lists>=6);
    }

    @Test public void routeSelectsOneMergeAndTheHybridIsTheDefault() {
        // nothing stored: the hybrid, on every phone
        assertEquals("hybrid",PreferenceKeys.mergeRoute());
        assertTrue(PreferenceKeys.isLmcHybridEnabled());assertTrue(PreferenceKeys.isVivoNiceEnabled());
        assertTrue(PreferenceKeys.isVivoHdrEnabled());assertFalse(PreferenceKeys.isScamHdrNiceEnabled());
        assertTrue(PreferenceKeys.isHybridShot());
        manager.set("default_scope",PreferenceKeys.ROUTE_KEY,"scamhdr");
        assertTrue(PreferenceKeys.isScamHdrSwitchOn());assertTrue(PreferenceKeys.isScamHdrNiceEnabled());
        assertFalse(PreferenceKeys.isLmcHybridEnabled());assertFalse(PreferenceKeys.isHybridShot());
        assertTrue(PreferenceKeys.isVivoNiceEnabled());
        manager.set("default_scope",PreferenceKeys.ROUTE_KEY,"something else");
        assertEquals("hybrid",PreferenceKeys.mergeRoute());
    }

    @Test public void hybridShotReadsItsOwnCopiesNeverScamHdrKeys() {
        manager.set("default_scope","pref_vivo_nice_noise_photon","3");
        manager.set("default_scope","pref_vivo_nice_zsl_frames","8");
        manager.set("default_scope","pref_vivo_nice_post_despeckle",false);
        manager.set("default_scope","pref_vivo_nice_cre_source","bundled");
        PreferenceKeys.beginShotProfile(true);
        assertTrue(PreferenceKeys.isHybridShot());assertTrue(PreferenceKeys.isNiceHybridEnabled());
        assertEquals(1f,PreferenceKeys.niceInternalValue("noise_photon",1f),0f);
        assertEquals(30,PreferenceKeys.getNiceZslFrames());assertEquals(30,PreferenceKeys.getHybridZslFrames());
        assertTrue(PreferenceKeys.isNiceDespeckleEnabled());
        assertFalse(PreferenceKeys.useStockBracketPlanner());
        assertEquals("auto",PreferenceKeys.getNiceCreSource());
        assertEquals("ark",PreferenceKeys.niceSharpenMode());
        manager.set("default_scope","pref_lmc_hybrid_noise_photon","2,5");
        manager.set("default_scope","pref_lmc_hybrid_zsl_frames","99");
        manager.set("default_scope","pref_lmc_hybrid_sharp_mode","off");
        assertEquals(2.5f,PreferenceKeys.niceInternalValue("noise_photon",1f),0f);
        assertEquals(44,PreferenceKeys.getNiceZslFrames());
        assertEquals("off",PreferenceKeys.niceSharpenMode());
        // "hybrid_<key>" always names a hybrid setting, in any profile.
        manager.set("default_scope","pref_lmc_hybrid_bento_factor","12");
        assertEquals(12f,PreferenceKeys.niceInternalValue("hybrid_bento_factor",8f),0f);
        assertEquals("pref_lmc_hybrid_ae_mid",PreferenceKeys.profileKey("pref_nice_ae_mid"));
        assertEquals("pref_lmc_hybrid_agx_knee_start",PreferenceKeys.profileKey("pref_agx_nice_knee_start"));
        assertEquals("pref_lmc_hybrid_hdr_gamma",PreferenceKeys.profileKey("pref_vivo_hdr_gamma"));
        PreferenceKeys.beginShotProfile(false);
        assertFalse(PreferenceKeys.isHybridShot());
        assertEquals(3f,PreferenceKeys.niceInternalValue("noise_photon",1f),0f);
        assertEquals(8,PreferenceKeys.getNiceZslFrames());
        assertFalse(PreferenceKeys.isNiceDespeckleEnabled());
        // the ARK sharpening is shared by both routes
        assertEquals("off",PreferenceKeys.niceSharpenMode());
        assertEquals("bundled",PreferenceKeys.getNiceCreSource());
        assertEquals("pref_nice_ae_mid",PreferenceKeys.profileKey("pref_nice_ae_mid"));
        assertEquals(12f,PreferenceKeys.niceInternalValue("hybrid_bento_factor",8f),0f);
        PreferenceKeys.endShotProfile();
    }

    @Test public void rawTherapeeStrengthScalesOnlyWhileAHybridShotIsProcessed() {
        float plain=PreferenceKeys.getSharpAmount();
        manager.set("default_scope","pref_lmc_hybrid_sharp_strength","0.5");
        manager.set("default_scope",PreferenceKeys.ROUTE_KEY,"hybrid");
        assertEquals(plain,PreferenceKeys.getSharpAmount(),0f);
        PreferenceKeys.beginShotProfile(true);
        assertEquals(plain*.5f,PreferenceKeys.getSharpAmount(),1e-6f);
        PreferenceKeys.beginShotProfile(false);
        assertEquals(plain,PreferenceKeys.getSharpAmount(),0f);
    }

    @Test public void tuningTextAndOutputUseTheNewKeys() {
        manager.set("default_scope","pref_lmc_hybrid_cdm","0.1");
        manager.set("default_scope","pref_lmc_hybrid_shasta",false);
        String tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("cdm 0.1\n"));assertTrue(tuning,tuning.contains("shastaEnable 0\n"));
        PreferenceKeys.setHybridOutputIndex(3);
        assertEquals("20",prefs.getString("pref_lmc_hybrid_output",""));assertEquals("20",PreferenceKeys.hybridOutputMode());
        PreferenceKeys.setHybridDownsamplerIndex(2);
        assertEquals("area",PreferenceKeys.hybridDownsampler());assertEquals(2,PreferenceKeys.hybridDownsamplerIndex());
        assertArrayEquals(new float[]{1,2},PreferenceKeys.hybridList("unset_list",new float[]{1,2}),0f);
        manager.set("default_scope","pref_lmc_hybrid_test_list","0.5, 3");
        assertArrayEquals(new float[]{.5f,3},PreferenceKeys.hybridList("test_list",new float[]{1,2}),0f);
        manager.set("default_scope","pref_lmc_hybrid_test_list","0.5,3,4");
        assertArrayEquals(new float[]{1,2},PreferenceKeys.hybridList("test_list",new float[]{1,2}),0f);
    }

    @Test public void roundFiveWorkerKeysReachTheTuningFile() {
        // Unset: the worker keeps its own defaults (Sabre 6.1 auto, cell clip, outlier sites, LMC Bento checks).
        String tuning=PreferenceKeys.hybridTuningText();
        for(String k:new String[]{"sabre61","hotSigma","cellClip","bentoLmc","s61MaxKey"})assertFalse(tuning,tuning.contains(k+" "));
        manager.set("default_scope","pref_lmc_hybrid_sabre61","0");
        manager.set("default_scope","pref_lmc_hybrid_s61_max_key","20");
        manager.set("default_scope","pref_lmc_hybrid_hot_sigma","0");
        manager.set("default_scope","pref_lmc_hybrid_cell_clip",false);
        manager.set("default_scope","pref_lmc_hybrid_bento_lmc",false);
        tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("sabre61 0.0\n"));assertTrue(tuning,tuning.contains("s61MaxKey 20.0\n"));
        assertTrue(tuning,tuning.contains("hotSigma 0.0\n"));
        assertTrue(tuning,tuning.contains("cellClip 0\n"));assertTrue(tuning,tuning.contains("bentoLmc 0\n"));
        // XML defaults equal the worker defaults (setDefaultValues writes them into every user's preferences).
        PreferenceScreen settings=inflate(),hybrid=settings.findPreference("lmc_hybrid_screen");
        assertEquals("2",((ListPreference)hybrid.findPreference("pref_lmc_hybrid_sabre61")).getEntryValues()[2].toString());
        assertNotNull(settings.findPreference("pref_lmc_hybrid_highlight_recovery"));
        assertNotNull(hybrid.findPreference("pref_lmc_hybrid_hot_base_sigma"));
    }

    @Test @Config(qualifiers="ru-w400dp-h880dp-mdpi") public void rawCaKeysReachTheTuningFileAndDefaultOff() {
        rawCaKeysReachTheTuningFileAndDefaultOff("Хроматическая аберрация RAW");
    }

    /** The same on an English system: the page title is English (i18n). */
    @Test public void rawCaKeysReachTheTuningFileAndDefaultOffInEnglish() {
        rawCaKeysReachTheTuningFileAndDefaultOff("RAW chromatic aberration");
    }

    private void rawCaKeysReachTheTuningFileAndDefaultOff(String pageTitle) {
        // P28: unset, nothing is written (worker default rawCa 0 = off, P19 as before)
        String tuning=PreferenceKeys.hybridTuningText();
        for(String k:new String[]{"rawCa","rawCaAuto","rawCaPasses","rawCaRed","rawCaBlue","rawCaAvoidShift"})assertFalse(tuning,tuning.contains(k+" "));
        manager.set("default_scope","pref_lmc_hybrid_rawca_mode","2");
        manager.set("default_scope","pref_lmc_hybrid_rawca_passes","3");
        manager.set("default_scope","pref_lmc_hybrid_rawca_auto",false);
        manager.set("default_scope","pref_lmc_hybrid_rawca_red","1.5");
        manager.set("default_scope","pref_lmc_hybrid_rawca_blue","-0.5");
        manager.set("default_scope","pref_lmc_hybrid_rawca_avoid_shift",false);
        tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("rawCa 2.0\n"));assertTrue(tuning,tuning.contains("rawCaPasses 3.0\n"));
        assertTrue(tuning,tuning.contains("rawCaAuto 0\n"));assertTrue(tuning,tuning.contains("rawCaRed 1.5\n"));
        assertTrue(tuning,tuning.contains("rawCaBlue -0.5\n"));assertTrue(tuning,tuning.contains("rawCaAvoidShift 0\n"));
        // the page: «Hybrid -> Склейка -> Хроматическая аберрация RAW», XML defaults = worker defaults (off, auto, 2 passes, avoid)
        PreferenceScreen settings=inflate(),merge=settings.findPreference("lmc_hybrid_merge_screen");
        PreferenceScreen page=merge.findPreference("lmc_hybrid_rawca_screen");
        assertNotNull(page);assertEquals(pageTitle,page.getTitle().toString());
        ListPreference mode=page.findPreference("pref_lmc_hybrid_rawca_mode");
        assertArrayEquals(new CharSequence[]{"0","1","2"},mode.getEntryValues());
        prefs.edit().clear().commit();
        settings=inflate();
        assertEquals("0",prefs.getString("pref_lmc_hybrid_rawca_mode","?"));
        assertTrue(prefs.getBoolean("pref_lmc_hybrid_rawca_auto",false));assertTrue(prefs.getBoolean("pref_lmc_hybrid_rawca_avoid_shift",false));
        tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("rawCa 0.0\n"));assertFalse(tuning,tuning.contains("rawCaAuto 0"));
    }

    @Test @Config(qualifiers="ru-w400dp-h880dp-mdpi") public void nativeMosaicKeysReachTheTuningFileAndDefaultToTheNativeMerge() {
        nativeMosaicKeysReachTheTuningFileAndDefaultToTheNativeMerge("Склейка мозаики","Нативная мозаика (по умолчанию)","Диапазон ядра");
    }

    /** The same on an English system (i18n). */
    @Test public void nativeMosaicKeysReachTheTuningFileAndDefaultToTheNativeMergeInEnglish() {
        nativeMosaicKeysReachTheTuningFileAndDefaultToTheNativeMerge("Mosaic merge","Native mosaic (default)","Kernel range");
    }

    private void nativeMosaicKeysReachTheTuningFileAndDefaultToTheNativeMerge(String pathTitle,String nativeEntry,String clampTitle) {
        // P29 / P34 / P35: unset, nothing is written (worker defaults: mosaicPath 1 = the native merge for Quad and, mosaicTetra 1,
        // for Tetra, kernel scale 0.7, edge scale 0.6, flat-area kernel x2.4, eigenvalue clamp)
        String[] keys={"mosaicPath","mosaicWindow","mosaicWindowFull","mosaicKernelScale","mosaicNativeEdgeScale","mosaicKernelG",
                "mosaicKernelRB","mosaicChromaFill","mosaicFillSupport","mosaicTetra","mosaicNativeFlatScale","mosaicNativeClamp",
                "mosaicNativeNightKernelScale","mosaicNativeNightEdgeScale"};
        String tuning=PreferenceKeys.hybridTuningText();
        for(String k:keys)assertFalse(tuning,tuning.contains(k+" "));
        manager.set("default_scope","pref_lmc_hybrid_mosaic_path","1");
        manager.set("default_scope","pref_lmc_hybrid_mosaic_window","2");
        manager.set("default_scope","pref_lmc_hybrid_mosaic_window_full",false);
        manager.set("default_scope","pref_lmc_hybrid_mosaic_kernel_scale","0.75");
        manager.set("default_scope","pref_lmc_hybrid_mosaic_native_edge_scale","0.3");
        manager.set("default_scope","pref_lmc_hybrid_mosaic_kernel_g","1.1");
        manager.set("default_scope","pref_lmc_hybrid_mosaic_kernel_rb","0.9");
        manager.set("default_scope","pref_lmc_hybrid_mosaic_chroma_fill","1");
        manager.set("default_scope","pref_lmc_hybrid_mosaic_fill_support","0.3");
        manager.set("default_scope","pref_lmc_hybrid_mosaic_tetra","2");
        manager.set("default_scope","pref_lmc_hybrid_mosaic_native_flat_scale","1.5");
        manager.set("default_scope","pref_lmc_hybrid_mosaic_native_clamp","1");
        manager.set("default_scope","pref_lmc_hybrid_mosaic_native_night_kernel_scale","0.8"); // dev keys without a row
        manager.set("default_scope","pref_lmc_hybrid_mosaic_native_night_edge_scale","0.5");
        tuning=PreferenceKeys.hybridTuningText();
        for(String line:new String[]{"mosaicPath 1.0","mosaicWindow 2.0","mosaicWindowFull 0","mosaicKernelScale 0.75","mosaicNativeEdgeScale 0.3",
                "mosaicKernelG 1.1","mosaicKernelRB 0.9","mosaicChromaFill 1.0","mosaicFillSupport 0.3","mosaicTetra 2.0",
                "mosaicNativeFlatScale 1.5","mosaicNativeClamp 1.0","mosaicNativeNightKernelScale 0.8","mosaicNativeNightEdgeScale 0.5"})
            assertTrue(line+" missing in "+tuning,tuning.contains(line+"\n"));
        // the page: Hybrid -> Merge -> Mosaic without remosaic; XML defaults = worker defaults (P34: the native merge for Quad with
        // window 3 full, kernel scale 0.7, edge scale 0.6, flat-area kernel x2.4, eigenvalue clamp, ks 1 / 0.85, no fill; P35: Tetra
        // native, T1)
        PreferenceScreen settings=inflate(),merge=settings.findPreference("lmc_hybrid_merge_screen");
        PreferenceScreen page=merge.findPreference("lmc_hybrid_mosaic_screen");
        assertNotNull(page);
        ListPreference path=page.findPreference("pref_lmc_hybrid_mosaic_path");
        assertEquals(pathTitle,path.getTitle().toString());
        assertArrayEquals(new CharSequence[]{"0","1"},path.getEntryValues());
        assertEquals(nativeEntry,path.getEntries()[1].toString());
        ListPreference tetra=page.findPreference("pref_lmc_hybrid_mosaic_tetra");
        assertArrayEquals(new CharSequence[]{"0","2","1"},tetra.getEntryValues());
        assertTrue(tetra.getEntries()[2].toString(),tetra.getEntries()[2].toString().startsWith("T1"));
        assertNotNull(page.findPreference("lmc_hybrid_mosaic_native_category"));
        ListPreference clamp=page.findPreference("pref_lmc_hybrid_mosaic_native_clamp");
        assertEquals(clampTitle,clamp.getTitle().toString());
        assertArrayEquals(new CharSequence[]{"0","1","2"},clamp.getEntryValues());
        assertNotNull(page.findPreference("pref_lmc_hybrid_mosaic_native_flat_scale"));
        assertNull(page.findPreference("pref_lmc_hybrid_mosaic_native_night_kernel_scale"));
        prefs.edit().clear().commit();
        settings=inflate();
        assertEquals("1",prefs.getString("pref_lmc_hybrid_mosaic_path","?"));
        assertTrue(prefs.getBoolean("pref_lmc_hybrid_mosaic_window_full",false));
        assertEquals("1",prefs.getString("pref_lmc_hybrid_mosaic_tetra","?"));
        assertEquals("2",prefs.getString("pref_lmc_hybrid_mosaic_native_clamp","?"));
        tuning=PreferenceKeys.hybridTuningText();
        for(String line:new String[]{"mosaicPath 1.0","mosaicWindow 3.0","mosaicKernelScale 0.7","mosaicNativeEdgeScale 0.6","mosaicKernelG 1.0",
                "mosaicKernelRB 0.85","mosaicChromaFill 0.0","mosaicFillSupport 0.25","mosaicTetra 1.0","mosaicNativeFlatScale 2.4",
                "mosaicNativeClamp 2.0"})
            assertTrue(line+" missing in "+tuning,tuning.contains(line+"\n"));
        assertFalse(tuning,tuning.contains("mosaicWindowFull"));
        assertFalse(tuning,tuning.contains("mosaicNativeNight"));
    }

    @Test public void migrationMovesHybridKeysCopiesSharedKnobsAndKeepsTheEffectiveRoute() {
        prefs.edit().clear()
                .putBoolean("pref_vivo_hdr_enabled",true).putBoolean("pref_vivo_nice_enabled",true)
                .putString("pref_vivo_nice_engine","hybrid")
                .putString("pref_vivo_nice_hybrid_post_luma","0.3").putString("pref_vivo_nice_post_luma","0.1")
                .putString("pref_vivo_nice_hybrid_bento_factor","16").putBoolean("pref_vivo_nice_hybrid_soft_tone",true)
                .putBoolean("pref_vivo_nice_hybrid_shasta",false).putString("pref_vivo_nice_hybrid_output","20")
                .putString("pref_vivo_nice_zsl_frames","20").putString("pref_vivo_nice_fusion_dark_ev","1.5")
                .putString("pref_nice_ae_mid","0.07").putString("pref_agx_nice_knee_start","1")
                .putString("pref_vivo_hdr_shadows","0.3").putString("pref_vivo_hdr_luma","1.2")
                .putString("pref_vivo_nice_sharp_mode","1").putString("pref_vivo_nice_luma","0")
                .putString("pref_lmc_hybrid_cdm","0.2").putString("pref_vivo_nice_hybrid_cdm","0.9")
                .commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("hybrid",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        for(String old:new String[]{"pref_lmc_hybrid_enabled","pref_vivo_hdr_enabled","pref_vivo_nice_enabled"})assertFalse(old,prefs.contains(old));
        assertEquals("0.3",prefs.getString("pref_lmc_hybrid_post_luma",""));
        assertFalse(prefs.contains("pref_lmc_hybrid_bento_factor"));assertFalse(prefs.contains("pref_lmc_hybrid_soft_tone"));
        assertFalse(prefs.getBoolean("pref_lmc_hybrid_shasta",true));assertEquals("20",prefs.getString("pref_lmc_hybrid_output",""));
        assertEquals("20",prefs.getString("pref_lmc_hybrid_zsl_frames",""));
        assertEquals("1.5",prefs.getString("pref_lmc_hybrid_fusion_dark_ev",""));
        assertEquals("0.07",prefs.getString("pref_lmc_hybrid_ae_mid",""));
        assertEquals("1",prefs.getString("pref_lmc_hybrid_agx_knee_start",""));
        assertEquals("0.3",prefs.getString("pref_lmc_hybrid_hdr_shadows",""));
        assertEquals("0.2",prefs.getString("pref_lmc_hybrid_cdm",""));
        assertFalse(prefs.contains("pref_lmc_hybrid_hdr_luma"));assertFalse(prefs.contains("pref_lmc_hybrid_sharp_mode"));
        assertFalse(prefs.contains("pref_lmc_hybrid_luma"));
        for(String key:prefs.getAll().keySet())assertFalse(key,key.startsWith("pref_vivo_nice_hybrid"));
        assertFalse(prefs.contains("pref_vivo_nice_engine"));
        // SCAM HDR keeps its own values.
        assertEquals("0.1",prefs.getString("pref_vivo_nice_post_luma",""));assertEquals("1.5",prefs.getString("pref_vivo_nice_fusion_dark_ev",""));
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals(.3f,PreferenceKeys.hybridValue("post_luma",.6f),1e-6f);
        assertEquals(8f,PreferenceKeys.hybridValue("bento_factor",8f),0f);

        prefs.edit().clear().putBoolean("pref_vivo_hdr_enabled",true).putBoolean("pref_vivo_nice_enabled",true)
                .putString("pref_vivo_nice_engine","nice").commit();
        SettingsMigration.migrateLmcHybrid(prefs,false);
        assertEquals("scamhdr",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        prefs.edit().clear().putBoolean("pref_vivo_hdr_enabled",true).putBoolean("pref_vivo_nice_enabled",true).commit();
        SettingsMigration.migrateLmcHybrid(prefs,false);
        assertEquals(PreferenceKeys.isVivoNetSoc()?"scamhdr":"hybrid",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        // SCAM HDR off and the hybrid off: the plain legacy route is gone, the hybrid takes it
        prefs.edit().clear().putBoolean("pref_vivo_hdr_enabled",false).putString("pref_vivo_nice_engine","hybrid").commit();
        SettingsMigration.migrateLmcHybrid(prefs,false);
        assertEquals("hybrid",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        prefs.edit().clear().commit();
        SettingsMigration.migrateLmcHybrid(prefs,true);
        assertEquals("hybrid",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        // after the separation: an explicit hybrid switch off with SCAM HDR on keeps SCAM HDR
        prefs.edit().clear().putBoolean("pref_lmc_hybrid_enabled",false).putBoolean("pref_vivo_hdr_enabled",true)
                .putBoolean("pref_vivo_nice_enabled",true).commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,true));
        assertEquals("scamhdr",prefs.getString(PreferenceKeys.ROUTE_KEY,""));assertFalse(prefs.contains("pref_lmc_hybrid_enabled"));
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,true));
        prefs.edit().clear().putBoolean("pref_lmc_hybrid_enabled",true).putBoolean("pref_vivo_hdr_enabled",true)
                .putBoolean("pref_vivo_nice_enabled",true).commit();
        SettingsMigration.migrateLmcHybrid(prefs,false);
        assertEquals("hybrid",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
    }

    @Test public void defaultsRevisionThreeMovesTheFormerSharpDefaultToArk() {
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid")
                .putString("pref_lmc_hybrid_sharp_mode","rt").putInt("pref_lmc_hybrid_defaults_rev",2).commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("ark",prefs.getString("pref_lmc_hybrid_sharp_mode",""));
        assertEquals(7,prefs.getInt("pref_lmc_hybrid_defaults_rev",0)); // revisions 6 / 7 mark at once (no mosaic key stored)
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid")
                .putString("pref_lmc_hybrid_sharp_mode","scam").commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("scam",prefs.getString("pref_lmc_hybrid_sharp_mode",""));
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        // after the revision a chosen RawTherapee stays
        prefs.edit().putString("pref_lmc_hybrid_sharp_mode","rt").commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("rt",prefs.getString("pref_lmc_hybrid_sharp_mode",""));
        // the former noise-reduction safeguards (stored XML defaults) become ArkCam's values; other values stay
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",2)
                .putString("pref_lmc_hybrid_dn_revert_max","2").putString("pref_lmc_hybrid_dn_coarse_stock","0.5")
                .putString("pref_lmc_hybrid_dn_chroma_floor","1.5").commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals(9f,Float.parseFloat(prefs.getString("pref_lmc_hybrid_dn_revert_max","")),0f);
        assertEquals(0f,Float.parseFloat(prefs.getString("pref_lmc_hybrid_dn_coarse_stock","")),0f);
        assertEquals("1.5",prefs.getString("pref_lmc_hybrid_dn_chroma_floor",""));
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        // revision 4: the former Shasta defaults (2 frames, EV 2) become ArkCam's (5 frames at x2); a chosen value stays
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",3)
                .putString("pref_lmc_hybrid_shasta_frames","2").putString("pref_lmc_hybrid_shasta_ev","3").commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals(5f,Float.parseFloat(prefs.getString("pref_lmc_hybrid_shasta_frames","")),0f);
        assertEquals(3f,Float.parseFloat(prefs.getString("pref_lmc_hybrid_shasta_ev","")),0f);
        assertEquals(7,prefs.getInt("pref_lmc_hybrid_defaults_rev",0)); // revisions 6 / 7 mark at once (no mosaic key stored)
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        // revision 5: a stored former default cdm 0.07 becomes 0.2; a chosen value stays
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",4)
                .putString("pref_lmc_hybrid_cdm","0.07").commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals(0.2f,Float.parseFloat(prefs.getString("pref_lmc_hybrid_cdm","")),1e-6f);
        assertEquals(7,prefs.getInt("pref_lmc_hybrid_defaults_rev",0)); // revisions 6 / 7 mark at once (no mosaic key stored)
        prefs.edit().putString("pref_lmc_hybrid_cdm","0.1").putInt("pref_lmc_hybrid_defaults_rev",4).commit();
        SettingsMigration.migrateLmcHybrid(prefs,false);
        assertEquals("0.1",prefs.getString("pref_lmc_hybrid_cdm",""));
        // revision 6 (P34): the former XML defaults of a P29 build (mosaic merge "0" = the split, kernel scale 1) move to the native
        // merge with kernel scale 0.7; chosen values stay, and a choice made after the revision is kept
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",5)
                .putString("pref_lmc_hybrid_mosaic_path","0").putString("pref_lmc_hybrid_mosaic_kernel_scale","1").commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("1",prefs.getString("pref_lmc_hybrid_mosaic_path",""));
        assertEquals(0.7f,Float.parseFloat(prefs.getString("pref_lmc_hybrid_mosaic_kernel_scale","")),1e-6f);
        assertEquals(7,prefs.getInt("pref_lmc_hybrid_defaults_rev",0)); // revision 7 marks at once (no "Tetra path" stored)
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        prefs.edit().putString("pref_lmc_hybrid_mosaic_path","0").commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("0",prefs.getString("pref_lmc_hybrid_mosaic_path",""));
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",5)
                .putString("pref_lmc_hybrid_mosaic_kernel_scale","0.5").commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("0.5",prefs.getString("pref_lmc_hybrid_mosaic_kernel_scale",""));
        // an upgrade without the mosaic keys stored (no rows before P34): the run marks revision 6, so the split and kernel scale 1
        // chosen after it (the screen first stored "1" / 0.7) stay on every later run
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",5).commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals(7,prefs.getInt("pref_lmc_hybrid_defaults_rev",0)); // revision 7 (P35) marks itself in the same run
        prefs.edit().putString("pref_lmc_hybrid_mosaic_path","0").putString("pref_lmc_hybrid_mosaic_kernel_scale","1").commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("0",prefs.getString("pref_lmc_hybrid_mosaic_path",""));
        assertEquals("1",prefs.getString("pref_lmc_hybrid_mosaic_kernel_scale",""));
        // a run that moves an older revision's value marks 6 and 7 as well (their keys are not stored), so the split and kernel
        // scale 1 chosen before the next run (the screen first stored "1" / 0.7) stay
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",4)
                .putString("pref_lmc_hybrid_cdm","0.07").commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals(0.2f,Float.parseFloat(prefs.getString("pref_lmc_hybrid_cdm","")),1e-6f);
        assertEquals(7,prefs.getInt("pref_lmc_hybrid_defaults_rev",0));
        prefs.edit().putString("pref_lmc_hybrid_mosaic_path","0").putString("pref_lmc_hybrid_mosaic_kernel_scale","1").commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("0",prefs.getString("pref_lmc_hybrid_mosaic_path",""));
        assertEquals("1",prefs.getString("pref_lmc_hybrid_mosaic_kernel_scale",""));
        // a run that copied legacy keys: revisions 6 and 7 mark on the next run, which sees the copied values
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",5)
                .putString("pref_vivo_nice_hybrid_mosaic_path","0").commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("0",prefs.getString("pref_lmc_hybrid_mosaic_path",""));
        assertEquals(5,prefs.getInt("pref_lmc_hybrid_defaults_rev",0));
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("1",prefs.getString("pref_lmc_hybrid_mosaic_path",""));
        assertEquals(7,prefs.getInt("pref_lmc_hybrid_defaults_rev",0));
    }

    /** P35 defaults revision 7: the stored former "Tetra path" default "0" (the split) moves to T1, chosen values stay. */
    @Test public void defaultsRevisionSevenMovesTheFormerTetraDefaultToTheNativeMerge() {
        // a P34 build stored "0" (its XML default) when the mosaic screen was opened: it moves to "1"
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",6)
                .putString("pref_lmc_hybrid_mosaic_tetra","0").commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("1",prefs.getString("pref_lmc_hybrid_mosaic_tetra",""));
        assertEquals(7,prefs.getInt("pref_lmc_hybrid_defaults_rev",0));
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        // the split chosen after the revision stays on every later run
        prefs.edit().putString("pref_lmc_hybrid_mosaic_tetra","0").commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("0",prefs.getString("pref_lmc_hybrid_mosaic_tetra",""));
        // a chosen T2 stays
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",6)
                .putString("pref_lmc_hybrid_mosaic_tetra","2").commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("2",prefs.getString("pref_lmc_hybrid_mosaic_tetra",""));
        assertEquals(7,prefs.getInt("pref_lmc_hybrid_defaults_rev",0));
        // from a P29 build (revision 5): revisions 6 and 7 move their former defaults in one run
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",5)
                .putString("pref_lmc_hybrid_mosaic_path","0").putString("pref_lmc_hybrid_mosaic_tetra","0").commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("1",prefs.getString("pref_lmc_hybrid_mosaic_path",""));
        assertEquals("1",prefs.getString("pref_lmc_hybrid_mosaic_tetra",""));
        assertEquals(7,prefs.getInt("pref_lmc_hybrid_defaults_rev",0));
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        // an upgrade from a P34 build without the key stored: the run only marks revision 7 (not a change), so the split chosen
        // after it (the screen first stored "1") stays
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",6).commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals(7,prefs.getInt("pref_lmc_hybrid_defaults_rev",0));
        prefs.edit().putString("pref_lmc_hybrid_mosaic_tetra","0").commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("0",prefs.getString("pref_lmc_hybrid_mosaic_tetra",""));
        // a run that moves an older revision's value marks revision 7 as well when the key is not stored, so the split chosen
        // before the next run (the screen first stored "1") stays
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",5)
                .putString("pref_lmc_hybrid_mosaic_path","0").commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("1",prefs.getString("pref_lmc_hybrid_mosaic_path",""));
        assertEquals(7,prefs.getInt("pref_lmc_hybrid_defaults_rev",0));
        prefs.edit().putString("pref_lmc_hybrid_mosaic_tetra","0").commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("0",prefs.getString("pref_lmc_hybrid_mosaic_tetra",""));
        // a run that copied legacy keys (here the "Tetra path" itself): revision 7 marks on the next run, which moves the copied "0"
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",6)
                .putString("pref_vivo_nice_hybrid_mosaic_tetra","0").commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("0",prefs.getString("pref_lmc_hybrid_mosaic_tetra",""));
        assertEquals(6,prefs.getInt("pref_lmc_hybrid_defaults_rev",0));
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("1",prefs.getString("pref_lmc_hybrid_mosaic_tetra",""));
        assertEquals(7,prefs.getInt("pref_lmc_hybrid_defaults_rev",0));
    }

    @Test public void hybridZslFramesFormerDefaultMovesTo30Once() {
        // a stored 20 is the former XML default: it moves to 30 once; the run reports the change, the next one does not
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",6)
                .putString("pref_lmc_hybrid_zsl_frames","20").commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("30",prefs.getString("pref_lmc_hybrid_zsl_frames",""));
        assertEquals(30,PreferenceKeys.getHybridZslFrames());
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        // 20 chosen after the move stays on every later run
        prefs.edit().putString("pref_lmc_hybrid_zsl_frames","20").commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("20",prefs.getString("pref_lmc_hybrid_zsl_frames",""));
        // any other stored value is the user's and stays; the marker is set anyway
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",6)
                .putString("pref_lmc_hybrid_zsl_frames","25").commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("25",prefs.getString("pref_lmc_hybrid_zsl_frames",""));
        assertEquals(1,prefs.getInt(SettingsMigration.ZSL_FRAMES_REV,0));
        // nothing stored (fresh install, the screen never shown): the default 30 applies, only the marker is written
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",6).commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertFalse(prefs.contains("pref_lmc_hybrid_zsl_frames"));
        assertEquals(30,PreferenceKeys.getHybridZslFrames());
        prefs.edit().putString("pref_lmc_hybrid_zsl_frames","20").commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("20",prefs.getString("pref_lmc_hybrid_zsl_frames",""));
    }

    @Test public void shotProfileStaysOnTheProcessingThread() throws Exception {
        manager.set("default_scope",PreferenceKeys.ROUTE_KEY,"scamhdr");
        PreferenceKeys.beginShotProfile(true);
        assertTrue(PreferenceKeys.isHybridShot());assertTrue(PreferenceKeys.isHybridShotProcessing());
        // The camera thread captures the next (SCAM HDR) shot meanwhile: it must see the live route, not this profile.
        final boolean[] other=new boolean[2];
        Thread camera=new Thread(()->{other[0]=PreferenceKeys.isHybridShot();other[1]=PreferenceKeys.isHybridShotProcessing();});
        camera.start();camera.join();
        assertFalse(other[0]);assertFalse(other[1]);
        PreferenceKeys.endShotProfile();
        assertFalse(PreferenceKeys.isHybridShot());
        manager.set("default_scope",PreferenceKeys.ROUTE_KEY,"hybrid");
        assertTrue(PreferenceKeys.isHybridShot());assertFalse(PreferenceKeys.isHybridShotProcessing());
    }

    @Test public void scamHdrKnobsReachTheHybridOnlyWhereItTookTheShotsAndOnlyOnce() {
        // SCAM HDR NICE users (engine nice, or auto on SM8750): the hybrid starts from its own defaults.
        prefs.edit().clear().putBoolean("pref_vivo_hdr_enabled",true).putBoolean("pref_vivo_nice_enabled",true)
                .putString("pref_vivo_nice_engine","nice").putString("pref_vivo_nice_zsl_frames","4")
                .putString("pref_vivo_nice_fusion_dark_ev","2").commit();
        SettingsMigration.migrateLmcHybrid(prefs,false);
        assertEquals("scamhdr",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        assertFalse(prefs.contains("pref_lmc_hybrid_zsl_frames"));assertFalse(prefs.contains("pref_lmc_hybrid_fusion_dark_ev"));
        assertEquals(30,PreferenceKeys.getHybridZslFrames());
        // A later run (engine key gone, "auto" off SM8750 would read as the hybrid) copies nothing either.
        prefs.edit().putString("pref_vivo_nice_fusion_detail","0.7").commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertFalse(prefs.contains("pref_lmc_hybrid_fusion_detail"));
        // Hybrid users: the knobs are copied once; the NICE-only noise sources mean auto for the hybrid.
        prefs.edit().clear().putBoolean("pref_vivo_hdr_enabled",true).putBoolean("pref_vivo_nice_enabled",true)
                .putString("pref_vivo_nice_engine","hybrid").putString("pref_vivo_nice_noise_source","imx06c")
                .putString("pref_vivo_nice_fusion_dark_ev","2").commit();
        SettingsMigration.migrateLmcHybrid(prefs,false);
        assertEquals("hybrid",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        assertEquals("2",prefs.getString("pref_lmc_hybrid_fusion_dark_ev",""));
        assertFalse(prefs.contains("pref_lmc_hybrid_noise_source"));
        prefs.edit().putString("pref_vivo_nice_fusion_detail","0.7").putString("pref_vivo_nice_fusion_dark_ev","3").commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertFalse(prefs.contains("pref_lmc_hybrid_fusion_detail"));
        assertEquals("2",prefs.getString("pref_lmc_hybrid_fusion_dark_ev",""));
        prefs.edit().clear().putBoolean("pref_vivo_hdr_enabled",true).putBoolean("pref_vivo_nice_enabled",true)
                .putString("pref_vivo_nice_engine","hybrid").putString("pref_vivo_nice_noise_source","settings").commit();
        SettingsMigration.migrateLmcHybrid(prefs,false);
        assertEquals("settings",prefs.getString("pref_lmc_hybrid_noise_source",""));
    }

    @Test public void freshInstallResetKeepsTheHybridRoute() {
        // A fresh install has no stored preference version: MigrationManager wipes the main preferences right after
        // SettingsManager migrated them; the hybrid's fresh-install state must be written again, before the XML defaults.
        context.getSharedPreferences(context.getPackageName()+PreferenceKeys.Key.KEY_PREF_VERSION.mValue,Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences("_has_set_default_values",Context.MODE_PRIVATE).edit().clear().commit();
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").commit();
        boolean readAgain=MigrationManager.readAgain;
        try {
            MigrationManager.migrate(manager);
            assertEquals("hybrid",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        } finally { MigrationManager.readAgain=readAgain; }
    }

    @Test public void restoredModuleSnapshotIsMigratedToo() {
        prefs.edit().clear().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,true).commit();
        ModuleProfiles profiles=PreferenceKeys.profiles();
        profiles.changed(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue);
        profiles.activate("back0");
        prefs.edit().putBoolean("pref_vivo_hdr_enabled",true).putBoolean("pref_vivo_nice_enabled",true)
                .putString("pref_vivo_nice_engine","hybrid").putString("pref_vivo_nice_hybrid_kernel","1.5")
                .remove(PreferenceKeys.ROUTE_KEY).commit();
        profiles.changed("pref_vivo_nice_hybrid_kernel");
        profiles.activate("back1");
        profiles.activate("back0");
        assertEquals("1.5",prefs.getString("pref_lmc_hybrid_kernel",""));
        assertFalse(prefs.contains("pref_vivo_nice_hybrid_kernel"));
        assertEquals("hybrid",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
    }
}
