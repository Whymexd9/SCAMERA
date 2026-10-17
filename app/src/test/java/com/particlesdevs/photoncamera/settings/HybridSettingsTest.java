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

/** The SCAM Hybrid as a section of its own: keys, switch independent of SCAM HDR, per-shot profile, migration. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class, qualifiers="w400dp-h880dp-mdpi")
public class HybridSettingsTest {
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

    /** P129: SHDR is the only merge; a stored SCAM HDR route is ignored; the NPU SoC check is the 8 Elite's only. */
    @Test public void shdrIsTheOnlyMergeAndTheNpuIsThe8Elites() {
        manager.set("default_scope", PreferenceKeys.ROUTE_KEY, ScamHybridKeys.ROUTE_SCAM_HDR);
        assertTrue(PreferenceKeys.isScamHdrSupported());
        assertFalse(PreferenceKeys.isScamHdrRoute());
        assertEquals(ScamHybridKeys.ROUTE_HYBRID, PreferenceKeys.mergeRoute());
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750-AC");
        assertTrue("8 Elite variants carry a suffix", PreferenceKeys.isScamHdrSupported());
        for (String other : new String[]{"SM8650", "SM8850", "MT6991", ""}) {
            org.robolectric.shadows.ShadowBuild.setSystemOnChipModel(other);
            assertFalse(other, PreferenceKeys.isScamHdrSupported());
            assertEquals(ScamHybridKeys.ROUTE_HYBRID, PreferenceKeys.mergeRoute());
        }
    }

    /**
     * P129 / P132: the SHDR section (noise reduction, sharpening, ARK tone, frames, merge); SCAM HDR and the route row gone. The
     * SHDR keys may sit in the groups an ordinary user looks in (resolution in «Фото», frames and «Движение» in «Съёмка», vibrance
     * in «Цвет», the noise source in «RAW и сенсор»), but they stay per-module values with numeric bounds.
     */
    @Test public void shdrSectionAndItsKeysWhereverTheyAre() {
        PreferenceScreen root=inflate();
        PreferenceScreen hybrid=root.findPreference("scam_hybrid_screen");
        assertNotNull(hybrid);assertEquals("SHDR",hybrid.getTitle().toString());
        for(String screen:new String[]{"scam_hybrid_photo_screen","scam_hybrid_ark_sharp_screen","scam_hybrid_ark_tone_screen",
                "scam_hybrid_capture_screen","scam_hybrid_merge_screen","scam_hybrid_nr_snr_screen","scam_hybrid_nr_mult_screen",
                "scam_hybrid_nr_safe_screen","scam_hybrid_rejection_screen","scam_hybrid_ark_rl_screen"})
            assertTrue(screen,hybrid.findPreference(screen) instanceof PreferenceScreen);
        // P175: the neural remosaic page is gone
        assertNull(root.findPreference("shdr_neural_remosaic_screen"));
        // P134: «Цвет» holds the colour rows (the vibrance page dissolved into its «Насыщенность»)
        assertTrue(root.findPreference("colour_mixer_screen") instanceof PreferenceScreen);
        assertTrue(root.findPreference("colour_grading_screen") instanceof PreferenceScreen);
        assertNull(root.findPreference("scam_hybrid_ark_vibrance_screen"));
        assertTrue(root.findPreference("scam_hybrid_motion_screen") instanceof PreferenceScreen);
        for(String gone:new String[]{"scam_hdr_screen","scam_hybrid_arkcore_screen","scam_hdr_ark_link","scam_probe",PreferenceKeys.ROUTE_KEY,
                "pref_scam_hybrid_enabled","sharp_settings_screen"})
            assertNull(gone,root.findPreference(gone));
        List<Preference> all=new ArrayList<>();collect(root,all);
        int hybridRows=0;
        for(Preference p:all){
            String key=p.getKey();if(key==null)continue;
            assertFalse(key,key.startsWith("pref_scamhdr_"));
            assertFalse(key,key.startsWith("pref_scamold_"));
            if(p instanceof PreferenceGroup||!key.startsWith(PreferenceKeys.HYBRID_PREFIX))continue;
            hybridRows++;
            assertTrue(key,ModuleProfiles.isLocal(key));
            if(p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.UniversalSeekBarPreference)
                assertNotNull(key+" needs numeric bounds",SettingsNumericRules.bounds(key));
        }
        assertTrue(hybridRows>45);
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
        PreferenceScreen hybrid=inflate().findPreference("scam_hybrid_screen");
        List<Preference> inside=new ArrayList<>();collect(hybrid,inside);
        int lists=0;
        for(Preference p:inside) if(p instanceof ListPreference&&p.getKey().startsWith(PreferenceKeys.HYBRID_PREFIX)){
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

    @Test public void shdrTakesEveryShot() {
        // nothing stored, a stored SCAM HDR route, anything: SHDR (P129)
        for(String stored:new String[]{null,"scamhdr","something else"}){
            if(stored!=null)manager.set("default_scope",PreferenceKeys.ROUTE_KEY,stored);
            assertEquals("hybrid",PreferenceKeys.mergeRoute());
            assertTrue(PreferenceKeys.isScamHybridEnabled());assertTrue(PreferenceKeys.isScamEnabled());
            assertFalse(PreferenceKeys.isScamHdrScamEnabled());assertFalse(PreferenceKeys.isScamHdrSwitchOn());
            assertTrue(PreferenceKeys.isHybridShot());
        }
    }

    @Test public void hybridShotReadsItsOwnCopiesNeverScamHdrKeys() {
        manager.set("default_scope","pref_scamhdr_noise_photon","3");
        manager.set("default_scope","pref_scamhdr_zsl_frames","8");
        manager.set("default_scope","pref_scamhdr_post_despeckle",false);
        manager.set("default_scope","pref_scamhdr_cre_source","bundled");
        PreferenceKeys.beginShotProfile(true);
        assertTrue(PreferenceKeys.isHybridShot());assertTrue(PreferenceKeys.isScamHybridShot());
        assertEquals(1f,PreferenceKeys.routeInternalValue("noise_photon",1f),0f);
        assertEquals(20,PreferenceKeys.getScamZslFrames());assertEquals(20,PreferenceKeys.getHybridZslFrames());
        assertTrue(PreferenceKeys.isScamDespeckleEnabled());
        assertFalse(PreferenceKeys.useStockBracketPlanner());
        assertEquals("auto",PreferenceKeys.getScamCreSource());
        manager.set("default_scope","pref_scam_hybrid_noise_photon","2,5");
        manager.set("default_scope","pref_scam_hybrid_zsl_frames","99");
        assertEquals(2.5f,PreferenceKeys.routeInternalValue("noise_photon",1f),0f);
        assertEquals(44,PreferenceKeys.getScamZslFrames());
        // "hybrid_<key>" always names a hybrid setting, in any profile.
        manager.set("default_scope","pref_scam_hybrid_bento_factor","12");
        assertEquals(12f,PreferenceKeys.routeInternalValue("hybrid_bento_factor",8f),0f);
        assertEquals("pref_scam_hybrid_ae_mid",PreferenceKeys.profileKey("pref_scamold_ae_mid"));
        assertEquals("pref_scam_hybrid_agx_knee_start",PreferenceKeys.profileKey("pref_agx_scam_knee_start"));
        assertEquals("pref_scam_hybrid_hdr_gamma",PreferenceKeys.profileKey("pref_scamroute_gamma"));
        PreferenceKeys.beginShotProfile(false);
        assertFalse(PreferenceKeys.isHybridShot());
        assertEquals(3f,PreferenceKeys.routeInternalValue("noise_photon",1f),0f);
        assertEquals(8,PreferenceKeys.getScamZslFrames());
        assertFalse(PreferenceKeys.isScamDespeckleEnabled());
        assertEquals("bundled",PreferenceKeys.getScamCreSource());
        assertEquals("pref_scamold_ae_mid",PreferenceKeys.profileKey("pref_scamold_ae_mid"));
        assertEquals(12f,PreferenceKeys.routeInternalValue("hybrid_bento_factor",8f),0f);
        PreferenceKeys.endShotProfile();
    }

    /**
     * P118 (owner, 2026-10-13): only the ARK sharpening stays. The page «Резкость» keeps the ARK rows; the mode list, the
     * RawTherapee page, «Доп. резкость после тона», the guard, the RawTherapee / SCAM amounts and the Sabre detail (used only
     * without ARK sharpening) are gone, and their stored values are dropped.
     */
    @Test public void onlyTheArkSharpeningStays() {
        PreferenceScreen root=inflate();
        PreferenceScreen sharp=root.findPreference("scam_hybrid_ark_sharp_screen");
        assertNotNull(sharp);
        for(String kept:new String[]{"pref_scam_hybrid_ark_sharp_note","pref_scam_hybrid_ark_sharp_gain","pref_scam_hybrid_ark_sharp_domain",
                "pref_scam_hybrid_ark_sharp_scale","pref_scam_hybrid_ark_sharp_usm_amount","pref_scam_hybrid_ark_sharp_bilateral_amount",
                "pref_scam_hybrid_ark_sharp_gf_lc","pref_scam_hybrid_ark_sharp_film_grain","pref_scam_hybrid_ark_sharp_rl1_amount",
                "pref_scam_hybrid_ark_sharp_halo_control"})
            assertNotNull(kept,sharp.findPreference(kept));
        String[] gone={"pref_scam_hybrid_sharp_mode","sharp_settings_screen","unsharp_settings_screen","deconvolution_settings_screen",
                "microcontrast_settings_screen","pref_sharp_usm_enabled_key","pref_sharp_radius_key","pref_sharp_deconv_enabled_key",
                "pref_sharp_micro_enabled_key","pref_scam_hybrid_ark_post_sharp","pref_scam_hybrid_ark_sharp_guard",
                "pref_scam_hybrid_sharp_strength","pref_scam_hybrid_sharp_amount","pref_scam_hybrid_ark_detail_gain"};
        for(String key:gone)assertNull(key,root.findPreference(key));
        // the ARK rows are never greyed for a sharpening mode
        Map<String,Object> s=new HashMap<>();s.put("pref_scam_hybrid_sharp_mode","off");
        assertNull(new SettingsAvailability(s).reason("pref_scam_hybrid_ark_sharp_gain"));
        assertNull(new SettingsAvailability(s).reason("pref_scam_hybrid_ark_sharp_rl1_amount"));
        prefs.edit().clear().putString("pref_scam_hybrid_sharp_mode","rt")
                .putBoolean("pref_scam_hybrid_ark_post_sharp",true).putString("pref_scam_hybrid_ark_sharp_guard","0.5")
                .putString("pref_scam_hybrid_sharp_strength","1").putString("pref_scam_hybrid_sharp_amount","1.2")
                .putString("pref_scam_hybrid_ark_detail_gain","2").putBoolean("pref_sharp_usm_enabled_key",true)
                .putString("pref_sharp_deconv_radius_1_key","1.0").putString("pref_scamhdr_sharp_mode","1")
                .putString("pref_scam_hybrid_ark_sharp_gain","1.5").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        assertEquals(java.util.Collections.singleton("pref_scam_hybrid_ark_sharp_gain"),prefs.getAll().keySet());
    }

    @Test public void tuningTextAndOutputUseTheNewKeys() {
        manager.set("default_scope","pref_scam_hybrid_cdm","0.1");
        manager.set("default_scope","pref_scam_hybrid_shasta",false);
        String tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("cdm 0.1\n"));assertTrue(tuning,tuning.contains("shastaEnable 0\n"));
        PreferenceKeys.setHybridOutputIndex(3);
        assertEquals("20",prefs.getString("pref_scam_hybrid_output",""));assertEquals("20",PreferenceKeys.hybridOutputMode());
        PreferenceKeys.setHybridDownsamplerIndex(2);
        assertEquals("area",PreferenceKeys.hybridDownsampler());assertEquals(2,PreferenceKeys.hybridDownsamplerIndex());
        assertArrayEquals(new float[]{1,2},PreferenceKeys.hybridList("unset_list",new float[]{1,2}),0f);
        manager.set("default_scope","pref_scam_hybrid_test_list","0.5, 3");
        assertArrayEquals(new float[]{.5f,3},PreferenceKeys.hybridList("test_list",new float[]{1,2}),0f);
        manager.set("default_scope","pref_scam_hybrid_test_list","0.5,3,4");
        assertArrayEquals(new float[]{1,2},PreferenceKeys.hybridList("test_list",new float[]{1,2}),0f);
    }

    @Test public void motionModeMergeValuesReachTheWorker() {
        // P86: the «Движение» values of boost / motion threshold / Bento went through hybridString, which knows no mode: the
        // worker merged with the photo values
        manager.set("default_scope","pref_scam_hybrid_boost_value","6");
        manager.set("default_scope","pref_scam_hybrid_motion_threshold","1.5");
        manager.set("default_scope","pref_scam_hybrid_bento","2");
        PreferenceKeys.setMotionMode(true);
        String tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("boost 10.0\n"));assertTrue(tuning,tuning.contains("motionThreshold 0.5\n"));
        assertTrue(tuning,tuning.contains("bento 1.0\n"));assertTrue(tuning,tuning.contains("widenBelow 0.0\n"));
        PreferenceKeys.setMotionMode(false);
        tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("boost 6.0\n"));assertTrue(tuning,tuning.contains("motionThreshold 1.5\n"));
        assertTrue(tuning,tuning.contains("bento 2.0\n"));
    }

    @Test public void roundFiveWorkerKeysReachTheTuningFile() {
        // Unset: the worker keeps its own defaults (cell clip, outlier sites, SCAM Bento checks); the Sabre 6.1 kernel has an app
        // default since P68 (always), written also when unset.
        String tuning=PreferenceKeys.hybridTuningText();
        for(String k:new String[]{"hotSigma","cellClip","bentoScam","s61MaxKey"})assertFalse(tuning,tuning.contains(k+" "));
        assertTrue(tuning,tuning.contains("sabre61 1.0\n"));
        manager.set("default_scope","pref_scam_hybrid_sabre61","0");
        manager.set("default_scope","pref_scam_hybrid_s61_max_key","20");
        manager.set("default_scope","pref_scam_hybrid_hot_sigma","0");
        manager.set("default_scope","pref_scam_hybrid_cell_clip",false);
        manager.set("default_scope","pref_scam_hybrid_bento_scam",false);
        tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("sabre61 0.0\n"));assertTrue(tuning,tuning.contains("s61MaxKey 20.0\n"));
        assertTrue(tuning,tuning.contains("hotSigma 0.0\n"));
        assertTrue(tuning,tuning.contains("cellClip 0\n"));assertTrue(tuning,tuning.contains("bentoScam 0\n"));
        // XML default = the app default (setDefaultValues writes it into every user's preferences): «Всегда» = "1".
        PreferenceScreen settings=inflate(),hybrid=settings.findPreference("scam_hybrid_screen");
        assertEquals("2",((ListPreference)hybrid.findPreference("pref_scam_hybrid_sabre61")).getEntryValues()[2].toString());
        prefs.edit().clear().commit();
        inflate();
        assertEquals("1",prefs.getString("pref_scam_hybrid_sabre61","?"));
        assertNotNull(settings.findPreference("pref_scam_hybrid_highlight_recovery"));
        assertNotNull(hybrid.findPreference("pref_scam_hybrid_hot_base_sigma"));
    }

    /** P118: rows that act only with another setting off its default are greyed with the reason; «Карта силы» has no duplicate. */
    @Test public void conditionalRowsFollowTheirSetting() {
        Map<String,Object> s=new HashMap<>();
        for(String k:new String[]{"pref_scam_hybrid_s61_max_key","pref_scam_hybrid_snr_scale","pref_scam_hybrid_shasta_sharpness",
                "pref_scam_hybrid_shasta_max_ratio","pref_scam_hybrid_bento_trigger"})
            assertNotNull(k,new SettingsAvailability(s).reason(k));
        assertNull(new SettingsAvailability(s).reason("pref_scam_hybrid_ark_agx_slope")); // Custom is the default look
        assertNull(new SettingsAvailability(s).reason("pref_scam_hybrid_motion_boost"));
        s.put("pref_scam_hybrid_sabre61","2");s.put("pref_scam_hybrid_shasta_mode","1");s.put("pref_scam_hybrid_bento","1");
        for(String k:new String[]{"pref_scam_hybrid_s61_max_key","pref_scam_hybrid_snr_scale","pref_scam_hybrid_shasta_sharpness",
                "pref_scam_hybrid_shasta_max_ratio","pref_scam_hybrid_bento_trigger"})
            assertNull(k,new SettingsAvailability(s).reason(k));
        s.put("pref_scam_hybrid_ark_agx_look","1");s.put("pref_scam_hybrid_local_align","0");
        assertNotNull(new SettingsAvailability(s).reason("pref_scam_hybrid_ark_agx_slope"));
        assertNotNull(new SettingsAvailability(s).reason("pref_scam_hybrid_motion_boost"));
        // Motion mode's own Bento (Auto) uses the trigger
        Map<String,Object> m=new HashMap<>();m.put("pref_motion_mode",true);
        assertNull(new SettingsAvailability(m).reason("pref_scam_hybrid_bento_trigger"));
        prefs.edit().clear().putString("pref_scam_hybrid_dn_strength_map","frames").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        assertEquals("auto",prefs.getString("pref_scam_hybrid_dn_strength_map",null));
    }

    /** P118: RAW CA as RawTherapee's CA_correct (P28) is gone: no page, a stored key never reaches the worker and is dropped. */
    @Test public void rawCaIsGone() {
        manager.set("default_scope","pref_scam_hybrid_rawca_mode","2");
        assertFalse(PreferenceKeys.hybridTuningText().contains("rawCa"));
        PreferenceScreen merge=inflate().findPreference("scam_hybrid_merge_screen");
        assertNotNull(merge);assertNull(merge.findPreference("scam_hybrid_rawca_screen"));
        assertTrue(SettingsMigration.isObsolete("pref_scam_hybrid_rawca_mode"));
    }

    @Test @Config(qualifiers="ru-w400dp-h880dp-mdpi") public void mochiKeysReachTheTuningFileAndDefaultAlways() {
        mochiKeysReachTheTuningFileAndDefaultAlways("Фотометрическая склейка");
    }

    @Test public void mochiKeysReachTheTuningFileAndDefaultAlwaysInEnglish() {
        mochiKeysReachTheTuningFileAndDefaultAlways("Photometric merge");
    }

    private void mochiKeysReachTheTuningFileAndDefaultAlways(String pageTitle) {
        // P68: unset, the app default 2 (always) is written (the worker's own default stays 0 for replays)
        String tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("mochi 2.0\n"));
        assertEquals(2,PreferenceKeys.getHybridMochi());

        manager.set("default_scope","pref_scam_hybrid_mochi","0");
        assertEquals(0,PreferenceKeys.getHybridMochi());
        tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("mochi 0.0\n"));

        manager.set("default_scope","pref_scam_hybrid_mochi","1");
        assertEquals(1,PreferenceKeys.getHybridMochi());
        tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("mochi 1.0\n"));

        manager.set("default_scope","pref_scam_hybrid_mochi","2");
        assertEquals(2,PreferenceKeys.getHybridMochi());
        tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("mochi 2.0\n"));

        // the page: «Hybrid -> Склейка -> Фотометрия Mochi», XML default = the app default (always: 2)
        PreferenceScreen settings=inflate(),merge=settings.findPreference("scam_hybrid_merge_screen");
        PreferenceScreen page=merge.findPreference("scam_hybrid_mochi_screen");
        assertNotNull(page);assertEquals(pageTitle,page.getTitle().toString());
        ListPreference mode=page.findPreference("pref_scam_hybrid_mochi");
        assertNotNull(mode);
        assertArrayEquals(new CharSequence[]{"0","1","2"},mode.getEntryValues());
        prefs.edit().clear().commit();
        settings=inflate();
        assertEquals("2",prefs.getString("pref_scam_hybrid_mochi","?"));
        tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("mochi 2.0\n"));
    }

    @Test public void p68DefaultsOnEveryPhone() {
        // owner, 10 October 2026: XML defaults stored on the first opening, and the same values written to the worker when unset
        String tuning=PreferenceKeys.hybridTuningText();
        for(String line:new String[]{"sabre61 1.0\n","mochi 2.0\n","bento 2.0\n","bentoFrames 3.0\n","shastaMotionMax 4.0\n","shastaForce 1\n"})
            assertTrue(line+tuning,tuning.contains(line));
        assertEquals(2,PreferenceKeys.getHybridShastaMode());
        assertEquals(20,PreferenceKeys.getHybridZslFrames());
        prefs.edit().clear().commit();
        inflate();
        assertEquals("20",prefs.getString("pref_scam_hybrid_zsl_frames","?"));
        // P190: «Кадров в снимке 50 / 200 Мп»: auto and 3 N + 1 ultrashort
        assertEquals("0",prefs.getString("pref_scam_hybrid_fullres50_frames","?"));
        assertEquals("4",prefs.getString("pref_scam_hybrid_fullres200_frames","?"));
        assertEquals(0,PreferenceKeys.getFullRes50Frames());assertEquals(4,PreferenceKeys.getFullRes200Frames());
        assertEquals("1",prefs.getString("pref_scam_hybrid_sabre61","?"));
        assertEquals("2",prefs.getString("pref_scam_hybrid_mochi","?"));
        assertEquals("2",prefs.getString("pref_scam_hybrid_bento","?"));
        assertEquals("3",prefs.getString("pref_scam_hybrid_bento_frames","?"));
        assertEquals("2",prefs.getString("pref_scam_hybrid_shasta_mode","?"));
        assertEquals(3f,Float.parseFloat(prefs.getString("pref_scam_hybrid_shasta_frames","?")),0f);
        assertEquals(4f,Float.parseFloat(prefs.getString("pref_scam_hybrid_shasta_motion_max","?")),0f);
        // Shasta auto: the worker's sharpness gate (no force line); Shasta off: disabled whatever the mode
        manager.set("default_scope","pref_scam_hybrid_shasta_mode","1");
        tuning=PreferenceKeys.hybridTuningText();
        assertFalse(tuning,tuning.contains("shastaForce"));assertFalse(tuning,tuning.contains("shastaEnable"));
        manager.set("default_scope","pref_scam_hybrid_shasta_mode","2");
        manager.set("default_scope","pref_scam_hybrid_shasta",false);
        assertEquals(0,PreferenceKeys.getHybridShastaMode());
        tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("shastaEnable 0\n"));assertFalse(tuning,tuning.contains("shastaForce"));
    }

    /** P118: «Опыты JSR» are gone; «Sabre 2× только когда есть смысл» is on by default, next to «Разрешение», live on the 2x grid. */
    @Test public void auto2xOnByDefaultAndJsrGone() {
        prefs.edit().clear().commit();
        PreferenceScreen settings=inflate();
        assertNull(settings.findPreference("scam_hybrid_jsr_screen"));
        PreferenceScreen photo=settings.findPreference("output_settings_screen");
        assertNotNull("P132: with the resolution in «Фото»",photo.findPreference("pref_scam_hybrid_auto2x"));
        assertTrue(prefs.getBoolean("pref_scam_hybrid_auto2x",false));
        String tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("auto2x 1\n"));
        for(String k:new String[]{"jsrPhase","jsrLca","jsrAuto2x","jsrPoly"})assertFalse(k+tuning,tuning.contains(k));
        manager.set("default_scope","pref_scam_hybrid_auto2x",false);
        assertFalse(PreferenceKeys.hybridTuningText().contains("auto2x"));
        assertTrue(SettingsMigration.isObsolete("pref_scam_hybrid_jsr_phase"));
        Map<String,Object> s=new HashMap<>();
        assertNotNull("sensor grid: nothing to decide",new SettingsAvailability(s).reason("pref_scam_hybrid_auto2x"));
        s.put("pref_scam_hybrid_output","16");assertNull(new SettingsAvailability(s).reason("pref_scam_hybrid_auto2x"));
        s.put("pref_scam_hybrid_output","2x");
        assertNotNull("native 2x cannot be replaced by a coarse merge",new SettingsAvailability(s).reason("pref_scam_hybrid_auto2x"));
        assertNull("chroma strength still acts on native 2x",new SettingsAvailability(s).reason("pref_scam_hybrid_dn_chroma_2x_keep"));
        s.put("pref_scam_hybrid_output","12");assertNull(new SettingsAvailability(s).reason("pref_scam_hybrid_auto2x"));
    }

    /** P79: the per-pass GPU times (a glFinish after every pass, 30-60 % slower merge) are off by default on every phone. */
    @Test public void gpuProfileOffByDefault() {
        assertFalse(PreferenceKeys.hybridTuningText().contains("profile "));
        org.robolectric.shadows.ShadowBuild.setManufacturer("Xiaomi");
        org.robolectric.shadows.ShadowBuild.setDevice("viva");
        assertFalse(PreferenceKeys.hybridTuningText().contains("profile "));
        // P79: the Redmi Note 11 Pro merges with the base frame's 6.1 covariance (owner allowed up to 10 % quality for speed)
        assertTrue(PreferenceKeys.hybridTuningText().contains("s61Mode 6\n"));
        org.robolectric.shadows.ShadowBuild.setDevice("nezha");
        assertFalse(PreferenceKeys.hybridTuningText().contains("profile "));
        assertFalse(PreferenceKeys.hybridTuningText().contains("s61Mode "));
        // the switch in the Hybrid diagnostics turns them on (owner, 2026-10-10)
        prefs.edit().putBoolean("pref_scam_hybrid_profile", true).commit();
        assertTrue(PreferenceKeys.hybridTuningText().contains("profile 1\n"));
        prefs.edit().remove("pref_scam_hybrid_profile").commit();
    }

    /** P164: the multiscale finish denoise has its own stored name; a former value becomes it, «nlm» stays. */
    @Test public void theDenoiseEngineValueTakesItsOwnName() {
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putString("pref_scam_hybrid_dn_engine","former").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("multiscale",prefs.getString("pref_scam_hybrid_dn_engine",""));
        prefs.edit().putString("pref_scam_hybrid_dn_engine","nlm").commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals("nlm",prefs.getString("pref_scam_hybrid_dn_engine",""));
    }

    @Test public void p68FormerDefaultsMoveOnce() {
        // stored former XML defaults move once; a value chosen before or after stays
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",7)
                .putInt(SettingsMigration.ZSL_FRAMES_REV,1).putString("pref_scam_hybrid_zsl_frames","30")
                .putString("pref_scam_hybrid_sabre61","2").putString("pref_scam_hybrid_mochi","0").putString("pref_scam_hybrid_bento","1")
                .putString("pref_scam_hybrid_bento_frames","2").putString("pref_scam_hybrid_shasta_frames","5").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("20",prefs.getString("pref_scam_hybrid_zsl_frames",""));
        assertEquals("1",prefs.getString("pref_scam_hybrid_sabre61",""));
        assertEquals("2",prefs.getString("pref_scam_hybrid_mochi",""));
        assertEquals("2",prefs.getString("pref_scam_hybrid_bento",""));
        assertEquals("3",prefs.getString("pref_scam_hybrid_bento_frames",""));
        assertEquals("3",prefs.getString("pref_scam_hybrid_shasta_frames",""));
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        // chosen after the move: stays
        prefs.edit().putString("pref_scam_hybrid_zsl_frames","30").putString("pref_scam_hybrid_sabre61","2").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("30",prefs.getString("pref_scam_hybrid_zsl_frames",""));assertEquals("2",prefs.getString("pref_scam_hybrid_sabre61",""));
        // chosen before (not a former default): stays, the marker is set anyway
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",7)
                .putInt(SettingsMigration.ZSL_FRAMES_REV,1).putString("pref_scam_hybrid_zsl_frames","25").putString("pref_scam_hybrid_sabre61","0").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("25",prefs.getString("pref_scam_hybrid_zsl_frames",""));assertEquals("0",prefs.getString("pref_scam_hybrid_sabre61",""));
        assertEquals(1,prefs.getInt(SettingsMigration.P68_DEFAULTS_REV,0));
        // a build older than the 20 -> 30 move: its stored 20 ends at today's 20 in one run
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",7)
                .putString("pref_scam_hybrid_zsl_frames","20").commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals("20",prefs.getString("pref_scam_hybrid_zsl_frames",""));
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("20",prefs.getString("pref_scam_hybrid_zsl_frames",""));
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
                "mosaicNativeNightKernelScale","mosaicNativeNightEdgeScale","mosaicTetraNightKernelScale","mosaicTetraNightEdgeScale",
                "mosaicTetraNightFlatScale"};
        String tuning=PreferenceKeys.hybridTuningText();
        for(String k:keys)assertFalse(tuning,tuning.contains(k+" "));
        manager.set("default_scope","pref_scam_hybrid_mosaic_path","1");
        manager.set("default_scope","pref_scam_hybrid_mosaic_window","2");
        manager.set("default_scope","pref_scam_hybrid_mosaic_window_full",false);
        manager.set("default_scope","pref_scam_hybrid_mosaic_kernel_scale","0.75");
        manager.set("default_scope","pref_scam_hybrid_mosaic_native_edge_scale","0.3");
        manager.set("default_scope","pref_scam_hybrid_mosaic_kernel_g","1.1");
        manager.set("default_scope","pref_scam_hybrid_mosaic_kernel_rb","0.9");
        manager.set("default_scope","pref_scam_hybrid_mosaic_chroma_fill","1");
        manager.set("default_scope","pref_scam_hybrid_mosaic_fill_support","0.3");
        manager.set("default_scope","pref_scam_hybrid_mosaic_tetra","2");
        manager.set("default_scope","pref_scam_hybrid_mosaic_native_flat_scale","1.5");
        manager.set("default_scope","pref_scam_hybrid_mosaic_native_clamp","1");
        manager.set("default_scope","pref_scam_hybrid_mosaic_native_night_kernel_scale","0.8"); // dev keys without a row
        manager.set("default_scope","pref_scam_hybrid_mosaic_native_night_edge_scale","0.5");
        manager.set("default_scope","pref_scam_hybrid_mosaic_tetra_night_kernel_scale","0.75");
        manager.set("default_scope","pref_scam_hybrid_mosaic_tetra_night_edge_scale","0.4");
        manager.set("default_scope","pref_scam_hybrid_mosaic_tetra_night_flat_scale","1.5");
        tuning=PreferenceKeys.hybridTuningText();
        for(String line:new String[]{"mosaicPath 1.0","mosaicWindow 2.0","mosaicWindowFull 0","mosaicKernelScale 0.75","mosaicNativeEdgeScale 0.3",
                "mosaicKernelG 1.1","mosaicKernelRB 0.9","mosaicChromaFill 1.0","mosaicFillSupport 0.3","mosaicTetra 2.0",
                "mosaicNativeFlatScale 1.5","mosaicNativeClamp 1.0","mosaicNativeNightKernelScale 0.8","mosaicNativeNightEdgeScale 0.5",
                "mosaicTetraNightKernelScale 0.75","mosaicTetraNightEdgeScale 0.4","mosaicTetraNightFlatScale 1.5"})
            assertTrue(line+" missing in "+tuning,tuning.contains(line+"\n"));
        // the page: Hybrid -> Merge -> Mosaic without remosaic; XML defaults = worker defaults (P34: the native merge for Quad with
        // window 3 full, kernel scale 0.7, edge scale 0.6, flat-area kernel x2.4, eigenvalue clamp, ks 1 / 0.85, no fill; P35: Tetra
        // native, T1)
        PreferenceScreen settings=inflate(),merge=settings.findPreference("scam_hybrid_merge_screen");
        PreferenceScreen page=merge.findPreference("scam_hybrid_mosaic_screen");
        assertNotNull(page);
        ListPreference path=page.findPreference("pref_scam_hybrid_mosaic_path");
        assertEquals(pathTitle,path.getTitle().toString());
        assertArrayEquals(new CharSequence[]{"0","1"},path.getEntryValues());
        assertEquals(nativeEntry,path.getEntries()[1].toString());
        ListPreference tetra=page.findPreference("pref_scam_hybrid_mosaic_tetra");
        assertArrayEquals(new CharSequence[]{"0","2","1"},tetra.getEntryValues());
        assertTrue(tetra.getEntries()[2].toString(),tetra.getEntries()[2].toString().startsWith("T1"));
        assertNotNull(page.findPreference("scam_hybrid_mosaic_native_category"));
        ListPreference clamp=page.findPreference("pref_scam_hybrid_mosaic_native_clamp");
        assertEquals(clampTitle,clamp.getTitle().toString());
        assertArrayEquals(new CharSequence[]{"0","1","2"},clamp.getEntryValues());
        assertNotNull(page.findPreference("pref_scam_hybrid_mosaic_native_flat_scale"));
        assertNull(page.findPreference("pref_scam_hybrid_mosaic_native_night_kernel_scale"));
        assertNull(page.findPreference("pref_scam_hybrid_mosaic_tetra_night_kernel_scale"));
        prefs.edit().clear().commit();
        settings=inflate();
        assertEquals("1",prefs.getString("pref_scam_hybrid_mosaic_path","?"));
        assertTrue(prefs.getBoolean("pref_scam_hybrid_mosaic_window_full",false));
        assertEquals("1",prefs.getString("pref_scam_hybrid_mosaic_tetra","?"));
        assertEquals("2",prefs.getString("pref_scam_hybrid_mosaic_native_clamp","?"));
        assertEquals("30",prefs.getString("pref_scam_hybrid_mosaic_frames","?")); // = the worker default mosaicFrames (7 October 2026)
        tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("mosaicFrames 30.0\n"));
        for(String line:new String[]{"mosaicPath 1.0","mosaicWindow 3.0","mosaicKernelScale 1.0","mosaicNativeEdgeScale 0.6","mosaicKernelG 1.0",
                "mosaicKernelRB 0.85","mosaicChromaFill 0.0","mosaicFillSupport 0.25","mosaicTetra 1.0","mosaicNativeFlatScale 2.4",
                "mosaicNativeClamp 2.0"})
            assertTrue(line+" missing in "+tuning,tuning.contains(line+"\n"));
        assertFalse(tuning,tuning.contains("mosaicWindowFull"));
        assertFalse(tuning,tuning.contains("mosaicNativeNight"));
        assertFalse(tuning,tuning.contains("mosaicTetraNight"));
    }

    @Test public void migrationMovesHybridKeysCopiesSharedKnobsAndKeepsTheEffectiveRoute() {
        prefs.edit().clear()
                .putBoolean("pref_scamroute_enabled",true).putBoolean("pref_scamhdr_enabled",true)
                .putString("pref_scamhdr_engine","hybrid")
                .putString("pref_scamhdr_hybrid_post_luma","0.3").putString("pref_scamhdr_post_luma","0.1")
                .putString("pref_scamhdr_hybrid_bento_factor","16").putBoolean("pref_scamhdr_hybrid_soft_tone",true)
                .putBoolean("pref_scamhdr_hybrid_shasta",false).putString("pref_scamhdr_hybrid_output","20")
                .putString("pref_scamhdr_zsl_frames","20").putString("pref_scamhdr_fusion_dark_ev","1.5")
                .putString("pref_scamold_ae_mid","0.07").putString("pref_agx_scam_knee_start","1")
                .putString("pref_scamroute_shadows","0.3").putString("pref_scamroute_luma","1.2")
                .putString("pref_scamhdr_sharp_mode","1").putString("pref_scamhdr_luma","0")
                .putString("pref_scam_hybrid_cdm","0.2").putString("pref_scamhdr_hybrid_cdm","0.9")
                .commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("hybrid",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        for(String old:new String[]{"pref_scam_hybrid_enabled","pref_scamroute_enabled","pref_scamhdr_enabled"})assertFalse(old,prefs.contains(old));
        assertEquals("0.3",prefs.getString("pref_scam_hybrid_post_luma",""));
        assertFalse(prefs.contains("pref_scam_hybrid_bento_factor"));assertFalse(prefs.contains("pref_scam_hybrid_soft_tone"));
        assertFalse(prefs.getBoolean("pref_scam_hybrid_shasta",true));assertEquals("20",prefs.getString("pref_scam_hybrid_output",""));
        assertEquals("20",prefs.getString("pref_scam_hybrid_zsl_frames",""));
        assertEquals("1.5",prefs.getString("pref_scam_hybrid_fusion_dark_ev",""));
        assertEquals("0.07",prefs.getString("pref_scam_hybrid_ae_mid",""));
        assertEquals("1",prefs.getString("pref_scam_hybrid_agx_knee_start",""));
        assertEquals("0.3",prefs.getString("pref_scam_hybrid_hdr_shadows",""));
        assertEquals("0.2",prefs.getString("pref_scam_hybrid_cdm",""));
        assertFalse(prefs.contains("pref_scam_hybrid_hdr_luma"));assertFalse(prefs.contains("pref_scam_hybrid_sharp_mode"));
        assertFalse(prefs.contains("pref_scam_hybrid_luma"));
        for(String key:prefs.getAll().keySet())assertFalse(key,key.startsWith("pref_scamhdr_hybrid"));
        assertFalse(prefs.contains("pref_scamhdr_engine"));
        // SCAM HDR keeps its own values.
        assertEquals("0.1",prefs.getString("pref_scamhdr_post_luma",""));assertEquals("1.5",prefs.getString("pref_scamhdr_fusion_dark_ev",""));
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals(.3f,PreferenceKeys.hybridValue("post_luma",.6f),1e-6f);
        assertEquals(8f,PreferenceKeys.hybridValue("bento_factor",8f),0f);

        prefs.edit().clear().putBoolean("pref_scamroute_enabled",true).putBoolean("pref_scamhdr_enabled",true)
                .putString("pref_scamhdr_engine","scam").commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals("scamhdr",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        prefs.edit().clear().putBoolean("pref_scamroute_enabled",true).putBoolean("pref_scamhdr_enabled",true).commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals(PreferenceKeys.isScamNetSoc()?"scamhdr":"hybrid",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        // SCAM HDR off and the hybrid off: the plain legacy route is gone, the hybrid takes it
        prefs.edit().clear().putBoolean("pref_scamroute_enabled",false).putString("pref_scamhdr_engine","hybrid").commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals("hybrid",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        prefs.edit().clear().commit();
        SettingsMigration.migrateScamHybrid(prefs,true);
        assertEquals("hybrid",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        // after the separation: an explicit hybrid switch off with SCAM HDR on keeps SCAM HDR
        prefs.edit().clear().putBoolean("pref_scam_hybrid_enabled",false).putBoolean("pref_scamroute_enabled",true)
                .putBoolean("pref_scamhdr_enabled",true).commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,true));
        assertEquals("scamhdr",prefs.getString(PreferenceKeys.ROUTE_KEY,""));assertFalse(prefs.contains("pref_scam_hybrid_enabled"));
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,true));
        prefs.edit().clear().putBoolean("pref_scam_hybrid_enabled",true).putBoolean("pref_scamroute_enabled",true)
                .putBoolean("pref_scamhdr_enabled",true).commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals("hybrid",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
    }

    /** P135: the stored former night denoise defaults (ArkCam SabreLow, revert limit 9) move once; chosen values stay. */
    @Test public void lowLightDenoiseDefaultsMoveOnce() {
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",9)
                .putString("pref_scam_hybrid_dn_luma_t1_strength","1,3,0,0,0").putString("pref_scam_hybrid_dn_luma_t1_revert","9,9,0.08,0.12,0")
                .putString("pref_scam_hybrid_dn_revert_max","9").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("1.2,1.1,1.2,1.1,1",prefs.getString("pref_scam_hybrid_dn_luma_t1_strength",""));
        assertEquals("0.6,0.5,0.08,0.12,0",prefs.getString("pref_scam_hybrid_dn_luma_t1_revert",""));
        assertEquals("1",prefs.getString("pref_scam_hybrid_dn_revert_max",""));
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        // a value chosen by hand stays, and a later 9 too (the marker is set)
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",9)
                .putString("pref_scam_hybrid_dn_luma_t1_revert","2,2,0.08,0.12,0").putString("pref_scam_hybrid_dn_revert_max","4").commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals("2,2,0.08,0.12,0",prefs.getString("pref_scam_hybrid_dn_luma_t1_revert",""));
        assertEquals("4",prefs.getString("pref_scam_hybrid_dn_revert_max",""));
        prefs.edit().putString("pref_scam_hybrid_dn_revert_max","9").commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals("9",prefs.getString("pref_scam_hybrid_dn_revert_max",""));
    }

    @Test public void defaultsRevisionThreeMovesTheFormerNoiseDefaultsToArk() {
        // the former noise-reduction safeguards (stored XML defaults) become ArkCam's values; other values stay
        // (its former sharp_mode "rt" -> "ark" step is gone with the mode list, P118)
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",2)
                .putString("pref_scam_hybrid_dn_revert_max","2").putString("pref_scam_hybrid_dn_coarse_stock","0.5")
                .putString("pref_scam_hybrid_dn_chroma_floor","1.5").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        // P135: ArkCam's 9 is then the night grain amplifier: the default limit is 1
        assertEquals(1f,Float.parseFloat(prefs.getString("pref_scam_hybrid_dn_revert_max","")),0f);
        assertEquals(0f,Float.parseFloat(prefs.getString("pref_scam_hybrid_dn_coarse_stock","")),0f);
        assertEquals("1.5",prefs.getString("pref_scam_hybrid_dn_chroma_floor",""));
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        // revision 4: the former Shasta defaults (2 frames, EV 2) become ArkCam's (5 frames at x2); a chosen value stays
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",3)
                .putString("pref_scam_hybrid_shasta_frames","2").putString("pref_scam_hybrid_shasta_ev","3").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals(5f,Float.parseFloat(prefs.getString("pref_scam_hybrid_shasta_frames","")),0f);
        assertEquals(3f,Float.parseFloat(prefs.getString("pref_scam_hybrid_shasta_ev","")),0f);
        assertEquals(7,prefs.getInt("pref_scam_hybrid_defaults_rev",0)); // revisions 6 / 7 mark at once (no mosaic key stored)
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        // revision 5: a stored former default cdm 0.07 becomes 0.2; a chosen value stays
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",4)
                .putString("pref_scam_hybrid_cdm","0.07").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals(0.2f,Float.parseFloat(prefs.getString("pref_scam_hybrid_cdm","")),1e-6f);
        assertEquals(7,prefs.getInt("pref_scam_hybrid_defaults_rev",0)); // revisions 6 / 7 mark at once (no mosaic key stored)
        prefs.edit().putString("pref_scam_hybrid_cdm","0.1").putInt("pref_scam_hybrid_defaults_rev",4).commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals("0.1",prefs.getString("pref_scam_hybrid_cdm",""));
        // revision 6 (P34): the former XML defaults of a P29 build (mosaic merge "0" = the split, kernel scale 1) move to the native
        // merge with kernel scale 0.7; chosen values stay, and a choice made after the revision is kept
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",5)
                .putString("pref_scam_hybrid_mosaic_path","0").putString("pref_scam_hybrid_mosaic_kernel_scale","1").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("1",prefs.getString("pref_scam_hybrid_mosaic_path",""));
        assertEquals(0.7f,Float.parseFloat(prefs.getString("pref_scam_hybrid_mosaic_kernel_scale","")),1e-6f);
        assertEquals(7,prefs.getInt("pref_scam_hybrid_defaults_rev",0)); // revision 7 marks at once (no "Tetra path" stored)
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        prefs.edit().putString("pref_scam_hybrid_mosaic_path","0").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("0",prefs.getString("pref_scam_hybrid_mosaic_path",""));
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",5)
                .putString("pref_scam_hybrid_mosaic_kernel_scale","0.5").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("0.5",prefs.getString("pref_scam_hybrid_mosaic_kernel_scale",""));
        // an upgrade without the mosaic keys stored (no rows before P34): the run marks revision 6, so the split and kernel scale 1
        // chosen after it (the screen first stored "1" / 0.7) stay on every later run
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",5).commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals(7,prefs.getInt("pref_scam_hybrid_defaults_rev",0)); // revision 7 (P35) marks itself in the same run
        prefs.edit().putString("pref_scam_hybrid_mosaic_path","0").putString("pref_scam_hybrid_mosaic_kernel_scale","1").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("0",prefs.getString("pref_scam_hybrid_mosaic_path",""));
        assertEquals("1",prefs.getString("pref_scam_hybrid_mosaic_kernel_scale",""));
        // a run that moves an older revision's value marks 6 and 7 as well (their keys are not stored), so the split and kernel
        // scale 1 chosen before the next run (the screen first stored "1" / 0.7) stay
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",4)
                .putString("pref_scam_hybrid_cdm","0.07").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals(0.2f,Float.parseFloat(prefs.getString("pref_scam_hybrid_cdm","")),1e-6f);
        assertEquals(7,prefs.getInt("pref_scam_hybrid_defaults_rev",0));
        prefs.edit().putString("pref_scam_hybrid_mosaic_path","0").putString("pref_scam_hybrid_mosaic_kernel_scale","1").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("0",prefs.getString("pref_scam_hybrid_mosaic_path",""));
        assertEquals("1",prefs.getString("pref_scam_hybrid_mosaic_kernel_scale",""));
        // a run that copied legacy keys: revisions 6 and 7 mark on the next run, which sees the copied values
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",5)
                .putString("pref_scamhdr_hybrid_mosaic_path","0").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("0",prefs.getString("pref_scam_hybrid_mosaic_path",""));
        assertEquals(5,prefs.getInt("pref_scam_hybrid_defaults_rev",0));
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("1",prefs.getString("pref_scam_hybrid_mosaic_path",""));
        assertEquals(7,prefs.getInt("pref_scam_hybrid_defaults_rev",0));
    }

    /** P35 defaults revision 7: the stored former "Tetra path" default "0" (the split) moves to T1, chosen values stay. */
    @Test public void defaultsRevisionSevenMovesTheFormerTetraDefaultToTheNativeMerge() {
        // a P34 build stored "0" (its XML default) when the mosaic screen was opened: it moves to "1"
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",6)
                .putString("pref_scam_hybrid_mosaic_tetra","0").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("1",prefs.getString("pref_scam_hybrid_mosaic_tetra",""));
        assertEquals(7,prefs.getInt("pref_scam_hybrid_defaults_rev",0));
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        // the split chosen after the revision stays on every later run
        prefs.edit().putString("pref_scam_hybrid_mosaic_tetra","0").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("0",prefs.getString("pref_scam_hybrid_mosaic_tetra",""));
        // a chosen T2 stays
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",6)
                .putString("pref_scam_hybrid_mosaic_tetra","2").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("2",prefs.getString("pref_scam_hybrid_mosaic_tetra",""));
        assertEquals(7,prefs.getInt("pref_scam_hybrid_defaults_rev",0));
        // from a P29 build (revision 5): revisions 6 and 7 move their former defaults in one run
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",5)
                .putString("pref_scam_hybrid_mosaic_path","0").putString("pref_scam_hybrid_mosaic_tetra","0").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("1",prefs.getString("pref_scam_hybrid_mosaic_path",""));
        assertEquals("1",prefs.getString("pref_scam_hybrid_mosaic_tetra",""));
        assertEquals(7,prefs.getInt("pref_scam_hybrid_defaults_rev",0));
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        // an upgrade from a P34 build without the key stored: the run only marks revision 7 (not a change), so the split chosen
        // after it (the screen first stored "1") stays
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",6).commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals(7,prefs.getInt("pref_scam_hybrid_defaults_rev",0));
        prefs.edit().putString("pref_scam_hybrid_mosaic_tetra","0").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("0",prefs.getString("pref_scam_hybrid_mosaic_tetra",""));
        // a run that moves an older revision's value marks revision 7 as well when the key is not stored, so the split chosen
        // before the next run (the screen first stored "1") stays
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",5)
                .putString("pref_scam_hybrid_mosaic_path","0").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("1",prefs.getString("pref_scam_hybrid_mosaic_path",""));
        assertEquals(7,prefs.getInt("pref_scam_hybrid_defaults_rev",0));
        prefs.edit().putString("pref_scam_hybrid_mosaic_tetra","0").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("0",prefs.getString("pref_scam_hybrid_mosaic_tetra",""));
        // a run that copied legacy keys (here the "Tetra path" itself): revision 7 marks on the next run, which moves the copied "0"
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",6)
                .putString("pref_scamhdr_hybrid_mosaic_tetra","0").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("0",prefs.getString("pref_scam_hybrid_mosaic_tetra",""));
        assertEquals(6,prefs.getInt("pref_scam_hybrid_defaults_rev",0));
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("1",prefs.getString("pref_scam_hybrid_mosaic_tetra",""));
        assertEquals(7,prefs.getInt("pref_scam_hybrid_defaults_rev",0));
    }

    @Test public void hybridZslFramesFormerDefaultMovesTo30Once() {
        // a stored 20 is the former XML default: it moves to 30 once; the run reports the change, the next one does not
        // (the P68 move 30 -> 20 marked as done: this rule alone; the chain is p68FormerDefaultsMoveOnce)
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",6)
                .putInt(SettingsMigration.P68_DEFAULTS_REV,1).putString("pref_scam_hybrid_zsl_frames","20").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("30",prefs.getString("pref_scam_hybrid_zsl_frames",""));
        assertEquals(30,PreferenceKeys.getHybridZslFrames());
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        // 20 chosen after the move stays on every later run
        prefs.edit().putString("pref_scam_hybrid_zsl_frames","20").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("20",prefs.getString("pref_scam_hybrid_zsl_frames",""));
        // any other stored value is the user's and stays; the marker is set anyway
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",6)
                .putInt(SettingsMigration.P68_DEFAULTS_REV,1).putString("pref_scam_hybrid_zsl_frames","25").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("25",prefs.getString("pref_scam_hybrid_zsl_frames",""));
        assertEquals(1,prefs.getInt(SettingsMigration.ZSL_FRAMES_REV,0));
        // nothing stored (fresh install, the screen never shown): the default (20 since P68) applies, only the marker is written
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",6).commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertFalse(prefs.contains("pref_scam_hybrid_zsl_frames"));
        assertEquals(20,PreferenceKeys.getHybridZslFrames());
        prefs.edit().putString("pref_scam_hybrid_zsl_frames","20").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("20",prefs.getString("pref_scam_hybrid_zsl_frames",""));
    }

    @Test public void mosaicFramesFormerDefaultMovesTo30Once() {
        // a stored 24 is the former XML default of «Frames in the mosaic merge»: it moves to 30 once, the next run reports nothing
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",7)
                .putString("pref_scam_hybrid_mosaic_frames","24").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("30",prefs.getString("pref_scam_hybrid_mosaic_frames",""));
        assertTrue(PreferenceKeys.hybridTuningText(),PreferenceKeys.hybridTuningText().contains("mosaicFrames 30.0\n"));
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        // 24 chosen after the move stays on every later run
        prefs.edit().putString("pref_scam_hybrid_mosaic_frames","24").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("24",prefs.getString("pref_scam_hybrid_mosaic_frames",""));
        // another stored value is the user's and stays; the marker is set anyway; the N frames' move is independent of it
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",7)
                .putInt(SettingsMigration.ZSL_FRAMES_REV,1).putString("pref_scam_hybrid_zsl_frames","20")
                .putString("pref_scam_hybrid_mosaic_frames","16").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("16",prefs.getString("pref_scam_hybrid_mosaic_frames",""));
        assertEquals("20",prefs.getString("pref_scam_hybrid_zsl_frames",""));
        assertEquals(1,prefs.getInt(SettingsMigration.MOSAIC_FRAMES_REV,0));
        // a build that already moved the N frames (its marker set) still moves a stored 24 of the mosaic merge
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",7)
                .putInt(SettingsMigration.ZSL_FRAMES_REV,1).putString("pref_scam_hybrid_mosaic_frames","24").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("30",prefs.getString("pref_scam_hybrid_mosaic_frames",""));
        // nothing stored: the default 30 (XML and worker) applies, only the marker is written; 24 chosen later stays
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",7).commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertFalse(prefs.contains("pref_scam_hybrid_mosaic_frames"));
        assertEquals(1,prefs.getInt(SettingsMigration.MOSAIC_FRAMES_REV,0));
        prefs.edit().putString("pref_scam_hybrid_mosaic_frames","24").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("24",prefs.getString("pref_scam_hybrid_mosaic_frames",""));
    }

    @Test public void frameCountMovesReachModuleProfilesOnceToo() {
        // module profiles stored by an older build (former defaults, no markers) move with the main preferences; a profile that
        // already went through the move keeps a 20 / 24 chosen afterwards
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").commit();
        SharedPreferences meta=context.getSharedPreferences("module_profiles_meta",Context.MODE_PRIVATE);
        meta.edit().clear().putBoolean("exists_back1",true).putBoolean("exists_back2",true).commit();
        SharedPreferences old=context.getSharedPreferences("module_profile_v2_back1",Context.MODE_PRIVATE);
        old.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt(SettingsMigration.P68_DEFAULTS_REV,1)
                .putString("pref_scam_hybrid_zsl_frames","20").putString("pref_scam_hybrid_mosaic_frames","24").commit();
        SharedPreferences moved=context.getSharedPreferences("module_profile_v2_back2",Context.MODE_PRIVATE);
        moved.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt(SettingsMigration.ZSL_FRAMES_REV,1)
                .putInt(SettingsMigration.MOSAIC_FRAMES_REV,1).putString("pref_scam_hybrid_zsl_frames","20")
                .putString("pref_scam_hybrid_mosaic_frames","24").commit();
        SettingsMigration.migrateScamHybrid(context,prefs);
        assertEquals("30",old.getString("pref_scam_hybrid_zsl_frames",""));assertEquals("30",old.getString("pref_scam_hybrid_mosaic_frames",""));
        assertEquals("20",moved.getString("pref_scam_hybrid_zsl_frames",""));assertEquals("24",moved.getString("pref_scam_hybrid_mosaic_frames",""));
        old.edit().putString("pref_scam_hybrid_mosaic_frames","24").commit();
        SettingsMigration.migrateScamHybrid(context,prefs);
        assertEquals("24",old.getString("pref_scam_hybrid_mosaic_frames",""));
        meta.edit().clear().commit();old.edit().clear().commit();moved.edit().clear().commit();
    }

    @Test public void shotProfileStaysOnTheProcessingThread() throws Exception {
        PreferenceKeys.beginShotProfile(true);
        assertTrue(PreferenceKeys.isHybridShot());assertTrue(PreferenceKeys.isHybridShotProcessing());
        // The camera thread captures the next shot meanwhile: SHDR too (P129), but it is not processing one
        final boolean[] other=new boolean[2];
        Thread camera=new Thread(()->{other[0]=PreferenceKeys.isHybridShot();other[1]=PreferenceKeys.isHybridShotProcessing();});
        camera.start();camera.join();
        assertTrue(other[0]);assertFalse(other[1]);
        PreferenceKeys.endShotProfile();
        assertTrue(PreferenceKeys.isHybridShot());assertFalse(PreferenceKeys.isHybridShotProcessing());
    }

    @Test public void scamHdrKnobsReachTheHybridOnlyWhereItTookTheShotsAndOnlyOnce() {
        // SCAM HDR SCAM users (engine scam, or auto on SM8750): the hybrid starts from its own defaults.
        prefs.edit().clear().putBoolean("pref_scamroute_enabled",true).putBoolean("pref_scamhdr_enabled",true)
                .putString("pref_scamhdr_engine","scam").putString("pref_scamhdr_zsl_frames","4")
                .putString("pref_scamhdr_fusion_dark_ev","2").commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals("scamhdr",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        assertFalse(prefs.contains("pref_scam_hybrid_zsl_frames"));assertFalse(prefs.contains("pref_scam_hybrid_fusion_dark_ev"));
        assertEquals(20,PreferenceKeys.getHybridZslFrames());
        // A later run (engine key gone, "auto" off SM8750 would read as the hybrid) copies nothing either.
        prefs.edit().putString("pref_scamhdr_fusion_detail","0.7").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertFalse(prefs.contains("pref_scam_hybrid_fusion_detail"));
        // Hybrid users: the knobs are copied once; the SCAM-only noise sources mean auto for the hybrid.
        prefs.edit().clear().putBoolean("pref_scamroute_enabled",true).putBoolean("pref_scamhdr_enabled",true)
                .putString("pref_scamhdr_engine","hybrid").putString("pref_scamhdr_noise_source","imx06c")
                .putString("pref_scamhdr_fusion_dark_ev","2").commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals("hybrid",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        assertEquals("2",prefs.getString("pref_scam_hybrid_fusion_dark_ev",""));
        assertFalse(prefs.contains("pref_scam_hybrid_noise_source"));
        prefs.edit().putString("pref_scamhdr_fusion_detail","0.7").putString("pref_scamhdr_fusion_dark_ev","3").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertFalse(prefs.contains("pref_scam_hybrid_fusion_detail"));
        assertEquals("2",prefs.getString("pref_scam_hybrid_fusion_dark_ev",""));
        prefs.edit().clear().putBoolean("pref_scamroute_enabled",true).putBoolean("pref_scamhdr_enabled",true)
                .putString("pref_scamhdr_engine","hybrid").putString("pref_scamhdr_noise_source","settings").commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals("settings",prefs.getString("pref_scam_hybrid_noise_source",""));
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
        prefs.edit().putBoolean("pref_scamroute_enabled",true).putBoolean("pref_scamhdr_enabled",true)
                .putString("pref_scamhdr_engine","hybrid").putString("pref_scamhdr_hybrid_kernel","1.5")
                .remove(PreferenceKeys.ROUTE_KEY).commit();
        profiles.changed("pref_scamhdr_hybrid_kernel");
        profiles.activate("back1");
        profiles.activate("back0");
        assertEquals("1.5",prefs.getString("pref_scam_hybrid_kernel",""));
        assertFalse(prefs.contains("pref_scamhdr_hybrid_kernel"));
        assertEquals("hybrid",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
    }

    /** P191: a stored 0.7 (the former XML default) moves to 1.0 once; any other value and a 0.7 chosen later stay. */
    @Test public void theFormerMosaicKernelDefaultMovesOnce() {
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",7).putString("pref_scam_hybrid_mosaic_kernel_scale","0.7").commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals(1f,Float.parseFloat(prefs.getString("pref_scam_hybrid_mosaic_kernel_scale","")),1e-6f);
        prefs.edit().putString("pref_scam_hybrid_mosaic_kernel_scale","0.7").commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals("0.7",prefs.getString("pref_scam_hybrid_mosaic_kernel_scale",""));
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",7).putString("pref_scam_hybrid_mosaic_kernel_scale","0.85").commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals("0.85",prefs.getString("pref_scam_hybrid_mosaic_kernel_scale",""));
    /**
     * P195 (owner, 2026-10-17: «настройка должна находиться в группе настроек "Мозаика без ремозаика" В самом верху»): «Наш ремозаик»
     * is the first row of the mosaic page, on by default (nothing written: the worker's mosaicRemosaic 1); off writes mosaicRemosaic 0
     * and leaves only the frame count live on the page.
     */
    @Test public void remosaicSwitchIsTheTopMosaicRowAndReachesTheWorker() {
        String tuning=PreferenceKeys.hybridTuningText();
        assertFalse(tuning,tuning.contains("mosaicRemosaic"));
        assertTrue(PreferenceKeys.isHybridMosaicRemosaic());
        PreferenceScreen settings=inflate(),merge=settings.findPreference("scam_hybrid_merge_screen");
        PreferenceScreen page=merge.findPreference("scam_hybrid_mosaic_screen");
        assertEquals("pref_scam_hybrid_mosaic_remosaic",page.getPreference(0).getKey());
        SwitchPreferenceCompat row=(SwitchPreferenceCompat)page.getPreference(0);
        assertTrue(row.isChecked());
        assertTrue(prefs.getBoolean("pref_scam_hybrid_mosaic_remosaic",false));
        manager.set("default_scope","pref_scam_hybrid_mosaic_remosaic",false);
        assertFalse(PreferenceKeys.isHybridMosaicRemosaic());
        tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("mosaicRemosaic 0\n"));
        Map<String,Object> values=new HashMap<>();
        values.put("pref_scam_hybrid_mosaic_remosaic",false);
        SettingsAvailability rules=new SettingsAvailability(values).mosaicStreams(true);
        assertNull(rules.reason("pref_scam_hybrid_mosaic_remosaic"));
        assertNull(rules.reason("pref_scam_hybrid_mosaic_frames"));
        assertNotNull(rules.reason("pref_scam_hybrid_mosaic_path"));
        assertNotNull(rules.reason("pref_scam_hybrid_mosaic_window"));
        values.put("pref_scam_hybrid_mosaic_remosaic",true);
        assertNull(new SettingsAvailability(values).mosaicStreams(true).reason("pref_scam_hybrid_mosaic_path"));
    }
}
