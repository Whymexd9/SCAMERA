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
        assertNotNull(hybrid);assertEquals("LMC-гибрид",hybrid.getTitle().toString());
        // Grouped like ArkCam 1.23: Функции обработки / Обработка фото / Обработка ArkCore (+ Диагностика).
        for(String screen:new String[]{"lmc_hybrid_functions_screen","lmc_hybrid_photo_screen","lmc_hybrid_arkcore_screen",
                "lmc_hybrid_ark_sharp_screen","lmc_hybrid_ark_rl_screen","lmc_hybrid_ark_tone_screen","lmc_hybrid_ark_vibrance_screen"})
            assertTrue(screen,hybrid.findPreference(screen) instanceof PreferenceScreen);
        for(String category:new String[]{"lmc_hybrid_main_category","lmc_hybrid_capture_category","lmc_hybrid_merge_category",
                "lmc_hybrid_nr_category","lmc_hybrid_nr_levels_category","lmc_hybrid_sharp_category",
                "lmc_hybrid_ark_artifacts_category","lmc_hybrid_diag_category"})
            assertTrue(category,hybrid.findPreference(category) instanceof PreferenceCategory);
        assertNull(root.findPreference("pref_lmc_hybrid_enabled"));
        assertTrue(root.findPreference(PreferenceKeys.ROUTE_KEY) instanceof androidx.preference.ListPreference);
        List<Preference> inside=new ArrayList<>();collect(hybrid,inside);
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
            if(key.startsWith(PreferenceKeys.HYBRID_PREFIX))assertTrue(key+" outside the hybrid section",insideKeys.contains(key));
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
        assertEquals(20,PreferenceKeys.getNiceZslFrames());assertEquals(20,PreferenceKeys.getHybridZslFrames());
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
        PreferenceScreen hybrid=inflate().findPreference("lmc_hybrid_screen");
        assertEquals("2",((ListPreference)hybrid.findPreference("pref_lmc_hybrid_sabre61")).getEntryValues()[2].toString());
        assertNotNull(hybrid.findPreference("pref_lmc_hybrid_highlight_recovery"));
        assertNotNull(hybrid.findPreference("pref_lmc_hybrid_hot_base_sigma"));
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
        assertEquals(3,prefs.getInt("pref_lmc_hybrid_defaults_rev",0));
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
        assertEquals(4,prefs.getInt("pref_lmc_hybrid_defaults_rev",0));
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        // revision 5: a stored former default cdm 0.07 becomes 0.2; a chosen value stays
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_lmc_hybrid_defaults_rev",4)
                .putString("pref_lmc_hybrid_cdm","0.07").commit();
        assertTrue(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals(0.2f,Float.parseFloat(prefs.getString("pref_lmc_hybrid_cdm","")),1e-6f);
        assertEquals(5,prefs.getInt("pref_lmc_hybrid_defaults_rev",0));
        prefs.edit().putString("pref_lmc_hybrid_cdm","0.1").putInt("pref_lmc_hybrid_defaults_rev",4).commit();
        SettingsMigration.migrateLmcHybrid(prefs,false);
        assertEquals("0.1",prefs.getString("pref_lmc_hybrid_cdm",""));
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
        assertEquals(20,PreferenceKeys.getHybridZslFrames());
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
