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
     * P129 / P132: the SHDR section (noise reduction, sharpening, WHY tone, frames, merge); SCAM HDR and the route row gone. The
     * SHDR keys may sit in the groups an ordinary user looks in (resolution in «Фото», frames and «Движение» in «Съёмка», vibrance
     * in «Цвет», the noise source in «RAW и сенсор»), but they stay per-module values with numeric bounds.
     */
    @Test public void shdrSectionAndItsKeysWhereverTheyAre() {
        PreferenceScreen root=inflate();
        // P406: no SHDR wrapper page - «Настройки изображения» holds the processing pages, as the reference app's categories
        assertNull(root.findPreference("scam_hybrid_screen"));
        PreferenceGroup hybrid=root.findPreference("root_colour");
        assertNotNull(hybrid);
        for(String screen:new String[]{"scam_hybrid_photo_screen","scam_hybrid_why_sharp_screen","scam_hybrid_why_tone_screen",
                "scam_hybrid_hdr_screen","scam_hybrid_merge_screen","scam_hybrid_nr_snr_screen","scam_hybrid_nr_mult_screen",
                "scam_hybrid_nr_safe_screen","scam_hybrid_mosaic_screen","scam_hybrid_why_rl_screen","photo_processing_screen"})
            assertTrue(screen,hybrid.findPreference(screen) instanceof PreferenceScreen);
        // P175: the neural remosaic page is gone
        assertNull(root.findPreference("shdr_neural_remosaic_screen"));
        // P134: «Цвет» holds the colour rows (the vibrance page dissolved into its «Насыщенность»)
        assertTrue(root.findPreference("colour_mixer_screen") instanceof PreferenceScreen);
        assertTrue(root.findPreference("colour_grading_screen") instanceof PreferenceScreen);
        assertNull(root.findPreference("scam_hybrid_why_vibrance_screen"));
        assertTrue(root.findPreference("scam_hybrid_motion_screen") instanceof PreferenceScreen);
        for(String gone:new String[]{"scam_hdr_screen","scam_hybrid_whycore_screen","scam_hdr_why_link","scam_probe",PreferenceKeys.ROUTE_KEY,
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
        // P406: the processing pages are rows of «Настройки изображения», as the reference app's categories
        PreferenceGroup hybrid=inflate().findPreference("root_colour");
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
     * P118 (owner, 2026-10-13): only the WHY sharpening stays. The page «Резкость» keeps the WHY rows; the mode list, the
     * RawTherapee page, «Доп. резкость после тона», the guard, the RawTherapee / SCAM amounts and the Sabre detail (used only
     * without WHY sharpening) are gone, and their stored values are dropped.
     */
    @Test public void onlyTheWhySharpeningStays() {
        PreferenceScreen root=inflate();
        PreferenceScreen sharp=root.findPreference("scam_hybrid_why_sharp_screen");
        assertNotNull(sharp);
        for(String kept:new String[]{"pref_scam_hybrid_why_sharp_note","pref_scam_hybrid_why_sharp_domain",
                "pref_scam_hybrid_why_sharp_scale","pref_scam_hybrid_why_sharp_usm_amount","pref_scam_hybrid_why_sharp_bilateral_amount",
                "pref_scam_hybrid_why_sharp_gf_lc","pref_scam_hybrid_why_sharp_film_grain",
                "pref_scam_hybrid_why_sharp_halo_control"})
            assertNotNull(kept,sharp.findPreference(kept));
        // P403: the deconvolution on its own page beside «Резкость» (as the reference app's «Деконволюция RL»)
        assertNotNull(((PreferenceGroup)root.findPreference("scam_hybrid_why_rl_screen")).findPreference("pref_scam_hybrid_why_sharp_rl1_amount"));
        // 2026-10-27 (owner: settings anyone can set up alone): the overall strength is «Резкость» of «Снимок»
        assertNotNull(((PreferenceGroup)root.findPreference("root_colour")).findPreference("pref_scam_hybrid_why_sharp_gain"));
        assertNull(sharp.findPreference("pref_scam_hybrid_why_sharp_gain"));
        String[] gone={"pref_scam_hybrid_sharp_mode","sharp_settings_screen","unsharp_settings_screen","deconvolution_settings_screen",
                "microcontrast_settings_screen","pref_sharp_usm_enabled_key","pref_sharp_radius_key","pref_sharp_deconv_enabled_key",
                "pref_sharp_micro_enabled_key","pref_scam_hybrid_why_post_sharp","pref_scam_hybrid_why_sharp_guard",
                "pref_scam_hybrid_sharp_strength","pref_scam_hybrid_sharp_amount","pref_scam_hybrid_why_detail_gain"};
        for(String key:gone)assertNull(key,root.findPreference(key));
        // the WHY rows are never greyed for a sharpening mode
        Map<String,Object> s=new HashMap<>();s.put("pref_scam_hybrid_sharp_mode","off");
        assertNull(new SettingsAvailability(s).reason("pref_scam_hybrid_why_sharp_gain"));
        assertNull(new SettingsAvailability(s).reason("pref_scam_hybrid_why_sharp_rl1_amount"));
        prefs.edit().clear().putString("pref_scam_hybrid_sharp_mode","rt")
                .putBoolean("pref_scam_hybrid_why_post_sharp",true).putString("pref_scam_hybrid_why_sharp_guard","0.5")
                .putString("pref_scam_hybrid_sharp_strength","1").putString("pref_scam_hybrid_sharp_amount","1.2")
                .putString("pref_scam_hybrid_why_detail_gain","2").putBoolean("pref_sharp_usm_enabled_key",true)
                .putString("pref_sharp_deconv_radius_1_key","1.0").putString("pref_scamhdr_sharp_mode","1")
                .putString("pref_scam_hybrid_why_sharp_gain","1.5").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        assertEquals(java.util.Collections.singleton("pref_scam_hybrid_why_sharp_gain"),prefs.getAll().keySet());
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
        // P344: «Движение» catches motion first - the hard knee and cdm 0.3 last (the worker keeps a key's last value)
        assertTrue(tuning,tuning.contains("rejKneeLo 0.1\nrejKneeHi 0.6\ncdm 0.3\n")&&tuning.lastIndexOf("cdm ")==tuning.lastIndexOf("cdm 0.3\n"));
        assertFalse(tuning,tuning.contains("dilateFloor 0.35\n"));
        PreferenceKeys.setMotionMode(false);
        tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("boost 6.0\n"));assertTrue(tuning,tuning.contains("motionThreshold 1.5\n"));
        assertTrue(tuning,tuning.contains("bento 2.0\n"));
    }

    @Test public void roundFiveWorkerKeysReachTheTuningFile() {
        // Unset: the worker keeps its own defaults (cell clip, outlier sites, SCAM Bento checks); the Sabre 6.1 kernel has an app
        // default since P68 (always), written also when unset.
        String tuning=PreferenceKeys.hybridTuningText();
        for(String k:new String[]{"hotSigma","bentoScam","s61MaxKey"})assertFalse(tuning,tuning.contains(k+" "));
        // P394: the whole-cell clip off by default (per-site clip: no toothed edges against a clipped light)
        assertTrue(tuning,tuning.contains("cellClip 0\n"));
        manager.set("default_scope","pref_scam_hybrid_cell_clip",true);
        assertFalse(PreferenceKeys.hybridTuningText().contains("cellClip "));
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
        PreferenceScreen settings=inflate();PreferenceGroup hybrid=settings.findPreference("root_colour");
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
        for(String k:new String[]{"pref_scam_hybrid_s61_min_motion","pref_scam_hybrid_snr_scale","pref_scam_hybrid_shasta_sharpness",
                "pref_scam_hybrid_shasta_max_ratio","pref_scam_hybrid_bento_trigger"})
            assertNotNull(k,new SettingsAvailability(s).reason(k));
        // P310 settings cleanup: the night / day boundary acts at every kernel setting (the worker's night noise and kernels)
        assertNull(new SettingsAvailability(s).reason("pref_scam_hybrid_s61_max_key"));
        assertNull(new SettingsAvailability(s).reason("pref_scam_hybrid_why_agx_slope")); // Custom is the default look
        assertNull(new SettingsAvailability(s).reason("pref_scam_hybrid_motion_boost"));
        assertNull(new SettingsAvailability(s).reason("pref_scam_hybrid_la_engine"));
        s.put("pref_scam_hybrid_sabre61","2");s.put("pref_scam_hybrid_shasta_mode","1");s.put("pref_scam_hybrid_bento","1");
        for(String k:new String[]{"pref_scam_hybrid_s61_max_key","pref_scam_hybrid_s61_min_motion","pref_scam_hybrid_snr_scale",
                "pref_scam_hybrid_shasta_sharpness","pref_scam_hybrid_shasta_max_ratio","pref_scam_hybrid_bento_trigger"})
            assertNull(k,new SettingsAvailability(s).reason(k));
        s.put("pref_scam_hybrid_why_agx_look","1");s.put("pref_scam_hybrid_local_align","0");
        assertNotNull(new SettingsAvailability(s).reason("pref_scam_hybrid_why_agx_slope"));
        assertNotNull(new SettingsAvailability(s).reason("pref_scam_hybrid_motion_boost"));
        assertNotNull(new SettingsAvailability(s).reason("pref_scam_hybrid_la_engine")); // P308: only with F6 on
        // P316: Auto's daylight rule and the boost's texture threshold read the F6 motion too
        assertNotNull(new SettingsAvailability(s).reason("pref_scam_hybrid_s61_min_motion"));
        assertNotNull(new SettingsAvailability(s).reason("pref_scam_hybrid_boost_threshold"));
        // Motion mode's own Bento (Auto) uses the trigger
        Map<String,Object> m=new HashMap<>();m.put("pref_motion_mode",true);
        assertNull(new SettingsAvailability(m).reason("pref_scam_hybrid_bento_trigger"));
        prefs.edit().clear().putString("pref_scam_hybrid_dn_strength_map","frames").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        assertEquals("auto",prefs.getString("pref_scam_hybrid_dn_strength_map",null));
    }

    /**
     * Settings audit 2026-10-21 (merge rows that act only on some routes): a row the shot's other settings leave out is greyed with
     * its reason, the lab shows the same (ScamLabFragment.inert).
     */
    @Test public void mergeRowsThatCannotActAreGreyed() {
        // «Движение» on: the merge reads the mode's own boost values, never widens the base; the variance threshold follows the
        // mode's boost switch (no android:dependency on the photo switch any more)
        Map<String,Object> m=new HashMap<>();
        for(String k:new String[]{"pref_scam_hybrid_motion_boost","pref_scam_hybrid_boost_value","pref_scam_hybrid_motion_threshold",
                "pref_scam_hybrid_widen_below","pref_scam_hybrid_boost_threshold"})
            assertNull(k,new SettingsAvailability(m).reason(k));
        m.put("pref_motion_mode",true);
        for(String k:new String[]{"pref_scam_hybrid_motion_boost","pref_scam_hybrid_boost_value","pref_scam_hybrid_motion_threshold",
                "pref_scam_hybrid_widen_below"})
            assertNotNull(k,new SettingsAvailability(m).reason(k));
        m.put("pref_scam_hybrid_motion_boost",false);
        assertNull(new SettingsAvailability(m).reason("pref_scam_hybrid_boost_threshold"));
        assertNull(new SettingsAvailability(m).reason("pref_scam_hybrid_mm_boost_value"));
        m.put("pref_scam_hybrid_mm_motion_boost",false);
        assertNotNull(new SettingsAvailability(m).reason("pref_scam_hybrid_boost_threshold"));
        m.put("pref_motion_mode",false);
        assertNotNull(new SettingsAvailability(m).reason("pref_scam_hybrid_boost_threshold"));
        m.put("pref_scam_hybrid_motion_boost",true);
        assertNull(new SettingsAvailability(m).reason("pref_scam_hybrid_boost_threshold"));
        assertNull(inflate().findPreference("pref_scam_hybrid_boost_threshold").getDependency());
        // the colour-difference clamp is a uniform of the pass that runs only with its strength above 0
        Map<String,Object> c=new HashMap<>();
        assertNull(new SettingsAvailability(c).reason("pref_scam_hybrid_chroma_diff_clamp"));
        c.put("pref_scam_hybrid_chroma_diff","0.4");
        assertNull(new SettingsAvailability(c).reason("pref_scam_hybrid_chroma_diff_clamp"));
        c.put("pref_scam_hybrid_chroma_diff","0");
        assertNotNull(new SettingsAvailability(c).reason("pref_scam_hybrid_chroma_diff_clamp"));
        // the edge and flat-area kernels of the mosaic merges are set inside the new kernel only
        Map<String,Object> k=new HashMap<>();
        String[] kernelRows={"pref_scam_hybrid_mosaic_native_edge_scale","pref_scam_hybrid_mosaic_native_flat_scale"};
        for(String sabre:new String[]{"1","2"}){
            k.put("pref_scam_hybrid_sabre61",sabre);
            for(String row:kernelRows)assertNull(row,new SettingsAvailability(k).mosaicStreams(true).reason(row));
        }
        k.put("pref_scam_hybrid_sabre61","0");
        for(String row:kernelRows)assertNotNull(row,new SettingsAvailability(k).mosaicStreams(true).reason(row));
        k.put("pref_scam_hybrid_mosaic_path","0"); // the split: its own edge kernel, also only in the new kernel
        assertNotNull(new SettingsAvailability(k).mosaicStreams(true).reason("pref_scam_hybrid_mosaic_edge_scale"));
        k.put("pref_scam_hybrid_sabre61","2");
        assertNull(new SettingsAvailability(k).mosaicStreams(true).reason("pref_scam_hybrid_mosaic_edge_scale"));
    }

    /**
     * Settings audit 2026-10-21 («Кадры светов и теней»): the frames' rows follow the switch the shot reads (the «Движение» mode's
     * own while it is on), the mode greys the photo rows it replaces, the shadow frames' kernel widening says when it can act, the
     * highlight kernel acts in the native mosaic merge only, and «цвет кромок светов» starts at 0.5.
     */
    @Test public void captureRowsFollowTheFramesInEffect() {
        String[] bentoRows={"pref_scam_hybrid_bento_frames","pref_scam_hybrid_bento_validate","pref_scam_hybrid_bento_motion_max",
                "pref_scam_hybrid_bento_factor","pref_scam_hybrid_bento_ramp","pref_scam_hybrid_bento_chroma_sigma","pref_scam_hybrid_bento_scam"};
        Map<String,Object> b=new HashMap<>();
        for(String k:bentoRows)assertNull(k,new SettingsAvailability(b).reason(k));
        b.put("pref_scam_hybrid_bento","0");
        for(String k:bentoRows)assertNotNull(k,new SettingsAvailability(b).reason(k));
        assertNotNull(new SettingsAvailability(b).reason("pref_scam_hybrid_bento_trigger"));
        assertNotNull(new SettingsAvailability(b).mosaicStreams(true).reason("pref_scam_hybrid_bento_sigma"));
        // «Движение»: its own Bento (Auto by default) rules them; the photo rows it replaces are greyed
        b.put("pref_motion_mode",true);
        for(String k:bentoRows)assertNull(k,new SettingsAvailability(b).reason(k));
        assertNull(new SettingsAvailability(b).reason("pref_scam_hybrid_bento_trigger"));
        for(String k:new String[]{"pref_scam_hybrid_zsl_frames","pref_scam_hybrid_bento","pref_scam_hybrid_shasta"})
            assertNotNull(k,new SettingsAvailability(b).reason(k));
        b.put("pref_scam_hybrid_mm_bento","0");
        for(String k:bentoRows)assertNotNull(k,new SettingsAvailability(b).reason(k));
        b.put("pref_motion_mode",false);
        assertNull(new SettingsAvailability(b).reason("pref_scam_hybrid_zsl_frames"));
        // shadow frames off: their sharpness, ratio and kernel widening rows; in «Движение» its own switch (off by default)
        Map<String,Object> s=new HashMap<>();s.put("pref_scam_hybrid_shasta_mode","1");s.put("pref_scam_hybrid_shasta_ev","2");
        for(String k:new String[]{"pref_scam_hybrid_shasta_sharpness","pref_scam_hybrid_shasta_max_ratio","pref_scam_hybrid_lut_sigma"}){
            assertNull(k,new SettingsAvailability(s).reason(k));
            s.put("pref_scam_hybrid_shasta",false);assertNotNull(k,new SettingsAvailability(s).reason(k));
            s.put("pref_scam_hybrid_shasta",true);s.put("pref_motion_mode",true);assertNotNull(k,new SettingsAvailability(s).reason(k));
            s.put("pref_scam_hybrid_mm_shasta",true);assertNull(k,new SettingsAvailability(s).reason(k));
            s.remove("pref_motion_mode");s.remove("pref_scam_hybrid_mm_shasta");
        }
        // the rest of the category the same way, with no android:dependency on the photo switch («Движение» greys that one, which
        // would grey them while the mode's own shadow frames are on)
        for(String k:new String[]{"pref_scam_hybrid_shasta_mode","pref_scam_hybrid_shasta_frames","pref_scam_hybrid_shasta_ev"}){
            assertNull(k,inflate().findPreference(k).getDependency());
            Map<String,Object> v=new HashMap<>();
            assertNull(k,new SettingsAvailability(v).reason(k));
            v.put("pref_scam_hybrid_shasta",false);assertNotNull(k,new SettingsAvailability(v).reason(k));
            v.put("pref_scam_hybrid_shasta",true);v.put("pref_motion_mode",true);assertNotNull(k,new SettingsAvailability(v).reason(k));
            v.put("pref_scam_hybrid_mm_shasta",true);assertNull(k,new SettingsAvailability(v).reason(k));
        }
        assertNull(inflate().findPreference("pref_scam_hybrid_shasta_motion_max").getDependency());
        // the motion limit of the long frames: «Всегда» only
        assertNotNull(new SettingsAvailability(s).reason("pref_scam_hybrid_shasta_motion_max"));
        s.put("pref_scam_hybrid_shasta_mode","2");assertNull(new SettingsAvailability(s).reason("pref_scam_hybrid_shasta_motion_max"));
        // the kernel widening needs a merge weight above 10: EV above 1.66, the cap above 10, the exponent high enough
        Map<String,Object> l=new HashMap<>();
        assertNotNull("the default EV 1",new SettingsAvailability(l).reason("pref_scam_hybrid_lut_sigma"));
        l.put("pref_scam_hybrid_shasta_ev","1.66");assertNotNull(new SettingsAvailability(l).reason("pref_scam_hybrid_lut_sigma"));
        l.put("pref_scam_hybrid_shasta_ev","1.7");assertNull(new SettingsAvailability(l).reason("pref_scam_hybrid_lut_sigma"));
        l.put("pref_scam_hybrid_weight_cap","10");assertNotNull(new SettingsAvailability(l).reason("pref_scam_hybrid_lut_sigma"));
        l.put("pref_scam_hybrid_weight_cap","50");l.put("pref_scam_hybrid_fwe","0.5");
        assertNotNull(new SettingsAvailability(l).reason("pref_scam_hybrid_lut_sigma"));
        l.put("pref_scam_hybrid_shasta_ev","4");assertNull(new SettingsAvailability(l).reason("pref_scam_hybrid_lut_sigma"));
        // the highlight kernel: the native Quad / Tetra merge only (not plain Bayer, the split, our remosaic off), unless the
        // highlight frames are mixed inside the merge (scam_dev bento_blend 0)
        Map<String,Object> k=new HashMap<>();
        assertNull(new SettingsAvailability(k).mosaicStreams(true).reason("pref_scam_hybrid_bento_sigma"));
        assertNotNull(new SettingsAvailability(k).mosaicStreams(false).reason("pref_scam_hybrid_bento_sigma"));
        assertNull(new SettingsAvailability(k).mosaicStreams(false).bentoBlend(false).reason("pref_scam_hybrid_bento_sigma"));
        k.put("pref_scam_hybrid_mosaic_path","0");
        assertNotNull(new SettingsAvailability(k).mosaicStreams(true).reason("pref_scam_hybrid_bento_sigma"));
        k.put("pref_scam_hybrid_mosaic_path","1");k.put("pref_scam_hybrid_mosaic_remosaic",false);
        assertNotNull(new SettingsAvailability(k).mosaicStreams(true).reason("pref_scam_hybrid_bento_sigma"));
        // «цвет кромок светов» from 0.5: a stored 0 (the blend took σ 2 for it) goes, 0.1..0.4 become 0.5
        assertEquals(0.5,SettingsNumericRules.bounds("pref_scam_hybrid_bento_chroma_sigma")[0],0);
        prefs.edit().clear().putString("pref_scam_hybrid_bento_chroma_sigma","0").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        assertFalse(prefs.contains("pref_scam_hybrid_bento_chroma_sigma"));
        prefs.edit().putString("pref_scam_hybrid_bento_chroma_sigma","0.3").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        assertEquals("0.5",prefs.getString("pref_scam_hybrid_bento_chroma_sigma",null));
        assertFalse(SettingsMigration.removeObsolete(prefs));
        prefs.edit().putString("pref_scam_hybrid_bento_chroma_sigma","1.5").commit();
        assertFalse(SettingsMigration.removeObsolete(prefs));
        assertEquals("1.5",prefs.getString("pref_scam_hybrid_bento_chroma_sigma",null));
    }

    /**
     * Settings audit 2026-10-21 (post rows that act only on some routes): greyed with the reason where the shot's other settings
     * (or the phone, or the profile edited) leave them out, live everywhere else.
     */
    @Test public void postRowsThatCannotActAreGreyed() {
        // «Сетка 2×: доля мелкого цвета»: the 2x grid, and every 200 MP shot (denoised on its 2x2 means at any resolution)
        Map<String,Object> s=new HashMap<>();
        assertNull("a phone with 200 MP shots",new SettingsAvailability(s).fullResSizes(false,true).reason("pref_scam_hybrid_dn_chroma_2x_keep"));
        assertNotNull(new SettingsAvailability(s).fullResSizes(true,false).reason("pref_scam_hybrid_dn_chroma_2x_keep"));
        s.put("pref_scam_hybrid_output","16");
        assertNull(new SettingsAvailability(s).fullResSizes(false,false).reason("pref_scam_hybrid_dn_chroma_2x_keep"));
        assertNotNull("auto 2x keeps its own rule",new SettingsAvailability(new HashMap<>()).fullResSizes(false,true).reason("pref_scam_hybrid_auto2x"));
        // NLM on a FULLRES 200 MP profile: its shots keep the merge alone
        Map<String,Object> n=new HashMap<>();n.put("pref_scam_hybrid_dn_engine","nlm");
        assertNull(new SettingsAvailability(n).reason("pref_scam_hybrid_post_chroma"));
        assertNotNull(new SettingsAvailability(n).editing200(true).reason("pref_scam_hybrid_post_chroma"));
        assertNotNull(new SettingsAvailability(n).editing200(true).reason("pref_scam_hybrid_post_luma"));
        assertNull(new SettingsAvailability(n).editing200(true).reason("pref_scam_hybrid_dn_engine"));
        // the few-frame boost limit: the multiscale denoise's strength map only, NLM always
        Map<String,Object> d=new HashMap<>();
        assertNull(new SettingsAvailability(d).reason("pref_scam_hybrid_bento_denoise_max"));
        d.put("pref_scam_hybrid_dn_strength_map","uniform");
        assertNotNull(new SettingsAvailability(d).reason("pref_scam_hybrid_bento_denoise_max"));
        d.put("pref_scam_hybrid_dn_engine","nlm");
        assertNull(new SettingsAvailability(d).reason("pref_scam_hybrid_bento_denoise_max"));
        // the OPPO matrix's saturation is AgX's: greyed under ACES, Uchimura and the own curve, live with every AgX look
        Map<String,Object> t=new HashMap<>();
        assertNull(new SettingsAvailability(t).reason("pref_scam_hybrid_why_ccm_sat"));
        t.put("pref_scam_hybrid_why_agx_look","1");
        assertNull(new SettingsAvailability(t).reason("pref_scam_hybrid_why_ccm_sat"));
        for(String op:new String[]{"pref_scam_hybrid_why_aces_on","pref_scam_hybrid_why_uchi_on","pref_scam_hybrid_why_curve_on"}){
            Map<String,Object> o=new HashMap<>();o.put(op,true);
            assertNotNull(op,new SettingsAvailability(o).reason("pref_scam_hybrid_why_ccm_sat"));
        }
        // the colour prior, the defringe and the clip band of the highlights: inside the per-channel recovery only
        String[] trio={"pref_scam_hybrid_highlight_chroma","pref_scam_hybrid_highlight_defringe","pref_scam_hybrid_highlight_band"};
        Map<String,Object> h=new HashMap<>();
        for(String k:trio)assertNull(k,new SettingsAvailability(h).reason(k));
        h.put("pref_scam_hybrid_highlight_recovery","0");
        for(String k:trio)assertNotNull(k,new SettingsAvailability(h).reason(k));
        h.put("pref_scam_hybrid_highlight_recovery",50);
        for(String k:trio)assertNull(k,new SettingsAvailability(h).reason(k));
        // clarity and the flat-area protection scale the WHY detail delta
        Map<String,Object> a=new HashMap<>();
        for(String k:new String[]{"pref_scam_hybrid_why_clarity","pref_scam_hybrid_why_flat_protect"}){
            a.remove("pref_scam_hybrid_why_sharp_gain");
            assertNull(k,new SettingsAvailability(a).reason(k));
            a.put("pref_scam_hybrid_why_sharp_gain","0");
            assertNotNull(k,new SettingsAvailability(a).reason(k));
            a.put("pref_scam_hybrid_why_sharp_gain","0.4");
            assertNull(k,new SettingsAvailability(a).reason(k));
        }
    }

    /**
     * Settings audit 2026-10-21: the worker gets a stored merge row as the capture getters read it - clamped to the row's bounds,
     * a decimal comma accepted - so a restored config cannot send a value the row cannot show; one that is no number leaves the
     * app default (else the worker's own).
     */
    @Test public void tuningTextClampsStoredRows() {
        manager.set("default_scope","pref_scam_hybrid_kernel","5");
        manager.set("default_scope","pref_scam_hybrid_dilate_floor","0,5");
        manager.set("default_scope","pref_scam_hybrid_bento","1.6");
        manager.set("default_scope","pref_scam_hybrid_mosaic_window","6");
        manager.set("default_scope","pref_scam_hybrid_cdm","x");
        String tuning=PreferenceKeys.hybridTuningText();
        for(String line:new String[]{"kernelScale 2.0","dilateFloor 0.5","bento 2.0","mosaicWindow 4.0"})
            assertTrue(line+" missing in "+tuning,tuning.contains(line+"\n"));
        assertFalse(tuning,tuning.contains("cdm "));
        manager.set("default_scope","pref_scam_hybrid_bento","many");
        assertTrue(PreferenceKeys.hybridTuningText().contains("bento 2.0\n")); // the app default (HYBRID_DEFAULTS)
        // «Радиус окна» lost 5 and 6: a stored one becomes 4 (main settings, module profiles, restored configs)
        prefs.edit().clear().putString("pref_scam_hybrid_mosaic_window","6").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        assertEquals("4",prefs.getString("pref_scam_hybrid_mosaic_window",null));
        assertFalse(SettingsMigration.removeObsolete(prefs));
        prefs.edit().clear().putString("pref_scam_hybrid_mosaic_window","3").commit();
        assertFalse(SettingsMigration.removeObsolete(prefs));
        assertEquals("3",prefs.getString("pref_scam_hybrid_mosaic_window",null));
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
        mochiKeysReachTheTuningFileAndDefaultAlways("Финальная сборка");
    }

    @Test public void mochiKeysReachTheTuningFileAndDefaultAlwaysInEnglish() {
        mochiKeysReachTheTuningFileAndDefaultAlways("Final assembly");
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

        // the row: «SHDR -> Склейка -> Финальная сборка» (P406, as the reference app), XML default = the app default (always: 2)
        PreferenceScreen settings=inflate(),merge=settings.findPreference("scam_hybrid_merge_screen");
        PreferenceGroup page=merge.findPreference("scam_hybrid_merge_final_category");
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

    @Test public void p309SoftUltrashortTransition() {
        // owner, 2026-10-20: «нам нужно сделать более мягкий переход между экспозициями»: the ramp on by default, 0 = the previous mask
        String tuning=PreferenceKeys.hybridTuningText();
        for(String line:new String[]{"bentoRamp 6.0\n","bentoRampLo 0.4\n","bentoRampHi 0.97\n","bentoRampReach 32.0\n"})
            assertTrue(line+tuning,tuning.contains(line));
        manager.set("default_scope","pref_scam_hybrid_bento_ramp","0");
        assertTrue(PreferenceKeys.hybridTuningText().contains("bentoRamp 0.0\n"));
        prefs.edit().clear().commit();
        inflate();
        assertEquals(6f,Float.parseFloat(prefs.getString("pref_scam_hybrid_bento_ramp","0")),0f);
    }

    @Test public void p310UltrashortBlendAfterTheMerge() {
        // owner, 2026-10-21 (LED fans of a PC: «куча артефактов на подсветке», «исправлять переходы»): the ultrashort frames
        // blended after the merge by region, always written (the worker default 0 keeps replays); its softness follows the
        // transition row in 2x2 cells (sigma ramp / 4, at least 0.25: 0 = the sharpest, no longer the default softness)
        String tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("bentoBlend 1\nbentoBlendSigma 1.5\n"));
        manager.set("default_scope","pref_scam_hybrid_bento_ramp","0");
        assertTrue(PreferenceKeys.hybridTuningText().contains("bentoBlendSigma 0.25\n"));
        manager.set("default_scope","pref_scam_hybrid_bento_ramp","12");
        assertTrue(PreferenceKeys.hybridTuningText().contains("bentoBlendSigma 3.0\n"));
    }

    @Test public void p366FlickerRulesOfTheUltrashortFrames() {
        // owner, 2026-10-27 (X200 Ultra ISZ at sea at noon: «вот такие фото получаю с исз, исправь»): the worker holds its lamp-flicker
        // corrections in daylight and for an incoherent tile field by its own defaults (no line); a changed threshold, 0 = off, is written
        String tuning=PreferenceKeys.hybridTuningText();
        assertFalse(tuning,tuning.contains("usDaylightEv")||tuning.contains("usIncoherent")||tuning.contains("usSceneEv"));
        manager.set("default_scope","pref_scam_hybrid_us_daylight_ev","0");
        manager.set("default_scope","pref_scam_hybrid_us_incoherent","0.7");
        tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("usDaylightEv 0.0\n")&&tuning.contains("usIncoherent 0.7\n"));
    }

    @Test public void p313UltrashortFillOfTheNativeMerge() {
        // owner, 2026-10-21 (vivo 2x ISZ in daylight: «все фото в области светов очень мыльное»): the native Quad / Tetra merge
        // keeps the N frames in Bento's cells, the ultrashort frames fill only what they clipped; always written (worker default 0)
        assertTrue(PreferenceKeys.hybridTuningText().contains("mosaicUsFill 1\n"));
    }

    @Test public void p308AlignmentEngineChoice() {
        // owner, 2026-10-20: «сделай гпу выравнивание и дай возможность выбора»: auto by default (written also when unset), GPU / CPU
        String tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("laGpu 2.0\n"));
        manager.set("default_scope","pref_scam_hybrid_la_engine","1");
        assertTrue(PreferenceKeys.hybridTuningText().contains("laGpu 1.0\n"));
        manager.set("default_scope","pref_scam_hybrid_la_engine","0");
        tuning=PreferenceKeys.hybridTuningText();
        assertTrue(tuning,tuning.contains("laGpu 0.0\n"));assertFalse(tuning,tuning.contains("laGpu 2.0\n"));
        prefs.edit().clear().commit();
        PreferenceScreen settings=inflate();
        assertEquals("2",prefs.getString("pref_scam_hybrid_la_engine","?"));
        ListPreference engine=settings.findPreference("pref_scam_hybrid_la_engine");
        assertNotNull(engine);
        assertArrayEquals(new CharSequence[]{"2","1","0"},engine.getEntryValues());
    }

    @Test public void p392DevSwitchesTakeTheirLinesOut() throws Exception {
        // P392 review: scam_dev.txt "hybrid_us_partial 0" takes the partial ultrashort refusal out of the worker's tuning and
        // "hybrid_long_scope 0" the long frames' block scope (each the worker as before P392), the other block stays
        String on=PreferenceKeys.hybridTuningText();
        assertTrue(on,on.contains("bentoPartial 1\nbentoAliasDamp 1\n")&&on.contains("shastaMotionScope 1\n"));
        java.io.File file=ScamDevFile.file(context);
        if(file.getParentFile()!=null)file.getParentFile().mkdirs();
        java.lang.reflect.Field stamp=PreferenceKeys.class.getDeclaredField("scamDevStamp");
        stamp.setAccessible(true);
        try{
            java.nio.file.Files.write(file.toPath(),"hybrid_us_partial 0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stamp.setLong(null,-1);
            String t=PreferenceKeys.hybridTuningText();
            assertFalse(t,t.contains("bentoPartial")||t.contains("bentoAliasDamp"));
            assertTrue(t,t.contains("shastaMotionScope 1\n"));
            java.nio.file.Files.write(file.toPath(),"hybrid_long_scope 0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stamp.setLong(null,-1);
            t=PreferenceKeys.hybridTuningText();
            assertFalse(t,t.contains("shastaMotionScope"));
            assertTrue(t,t.contains("bentoPartial 1\nbentoAliasDamp 1\n"));
        }finally{
            java.nio.file.Files.deleteIfExists(file.toPath());
            stamp.setLong(null,-1);
        }
        assertTrue(PreferenceKeys.hybridTuningText().contains("bentoPartial 1\n"));
    }

    @Test public void p68DefaultsOnEveryPhone() {
        // owner, 10 October 2026: XML defaults stored on the first opening, and the same values written to the worker when unset
        String tuning=PreferenceKeys.hybridTuningText();
        for(String line:new String[]{"sabre61 1.0\n","mochi 2.0\n","bento 2.0\n","bentoFrames 3.0\n","shastaMotionMax 4.0\n","shastaForce 1\n",
                "rejPrior 1\n","baseDemosaicAlias 1.0\n"}) // P335
            assertTrue(line+tuning,tuning.contains(line));
        assertFalse("P336: the green-site 6.1 tensor is off by default",tuning.contains("s61Fine"));
        assertTrue("P337: the night 6.1 kernel x0.8",tuning.contains("s61NightScale 0.8\n"));
        assertTrue("P337: glitter keeps its sparkles",tuning.contains("clipReject 1\n")&&tuning.contains("rimLift 1\n"));
        assertTrue("P339: highlight frames only with a clipped base",tuning.contains("usNeedClip 1\n"));
        assertTrue("P344: the rejection set",tuning.contains("rejPool 3\n")&&tuning.contains("rejMatchBlur 1\n")
                &&tuning.contains("rejKneeLo 0.05\nrejKneeHi 0.8\n")&&!tuning.contains("rejPool 1\n"));
        assertTrue("P350: the ultrashort mask set",tuning.contains("bentoUsRegister 2\nlaUltrashort 1\n")&&tuning.contains("bentoRefuseScope 1\n")
                &&tuning.lastIndexOf("bentoMotionMax ")==tuning.indexOf("bentoMotionMax 25\n"));
        assertTrue("P358: the reject pass skips the ultrashort donors, the long frames' measured noise and weight",
                tuning.contains("rejSkipUs 1\n")&&tuning.contains("noiseRoles 1\nshastaNoiseWeight 0.005\n"));
        assertTrue("P392: partial ultrashort refusal and the long frames' block scope, after the P350 set",
                tuning.contains("bentoPartial 1\nbentoAliasDamp 1\n")&&tuning.contains("shastaMotionScope 1\n")
                &&tuning.indexOf("bentoPartial 1\n")>tuning.indexOf("bentoRefuseScope 1\n"));
        assertTrue("P344: the spread floor over the untouched default",tuning.lastIndexOf("dilateFloor ")==tuning.indexOf("dilateFloor 0.35\n"));
        assertEquals(2,PreferenceKeys.getHybridShastaMode());
        assertEquals(20,PreferenceKeys.getHybridZslFrames());
        prefs.edit().clear().commit();
        inflate();
        assertEquals("20",prefs.getString("pref_scam_hybrid_zsl_frames","?"));
        // P190: «Кадров в снимке 50 / 200 Мп»: auto and 3 N + 1 ultrashort
        assertEquals("P203: 12 N", "12",prefs.getString("pref_scam_hybrid_fullres50_frames","?"));
        assertEquals("4",prefs.getString("pref_scam_hybrid_fullres200_frames","?"));
        assertEquals(12,PreferenceKeys.getFullRes50Frames());assertEquals(4,PreferenceKeys.getFullRes200Frames());
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
        // 2026-10-27: Auto 2× is a fine setting of the size after the merge; P406: on «Размер фото» («Сохранение и форматы фото»)
        PreferenceScreen advanced=settings.findPreference("photo_size_screen");
        assertNotNull("in «Размер фото › Сетка 2× и уменьшение»",advanced.findPreference("pref_scam_hybrid_auto2x"));
        assertEquals("adv_output",((Preference)settings.findPreference("pref_scam_hybrid_auto2x")).getParent().getKey());
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

    /** P135: the stored former night denoise defaults (WhyCam SabreLow, revert limit 9) move once; chosen values stay. */
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

    @Test public void defaultsRevisionThreeMovesTheFormerNoiseDefaultsToWhy() {
        // the former noise-reduction safeguards (stored XML defaults) become WhyCam's values; other values stay
        // (its former sharp_mode "rt" -> "why" step is gone with the mode list, P118)
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",2)
                .putString("pref_scam_hybrid_dn_revert_max","2").putString("pref_scam_hybrid_dn_coarse_stock","0.5")
                .putString("pref_scam_hybrid_dn_chroma_floor","1.5").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        // P135: WhyCam's 9 is then the night grain amplifier: the default limit is 1
        assertEquals(1f,Float.parseFloat(prefs.getString("pref_scam_hybrid_dn_revert_max","")),0f);
        assertEquals(0f,Float.parseFloat(prefs.getString("pref_scam_hybrid_dn_coarse_stock","")),0f);
        assertEquals("1.5",prefs.getString("pref_scam_hybrid_dn_chroma_floor",""));
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        // revision 4: the former Shasta defaults (2 frames, EV 2) become WhyCam's (5 frames at x2); a chosen value stays
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

    @Test public void referenceConfigDefaultsMoveOnce() throws Exception {
        // P405: the XML's default of every row is the reference config's value of the table
        final String xml = new String(java.nio.file.Files.readAllBytes(new java.io.File(new java.io.File("src/main/res/xml/preferences.xml").isFile()
                ? "src/main/res/xml/preferences.xml" : "app/src/main/res/xml/preferences.xml").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        for (Object[] row : SettingsMigration.CONFIG_DEFAULTS) {
            final String key = "pref_scam_hybrid_" + row[0];
            final java.util.regex.Matcher m = java.util.regex.Pattern.compile("<[^>]*android:key=\"" + key + "\"[^>]*>").matcher(xml);
            assertTrue(key, m.find());
            final java.util.regex.Matcher d = java.util.regex.Pattern.compile("android:defaultValue=\"([^\"]*)\"").matcher(m.group());
            if (row[0].equals("why_curve_points")) { assertFalse("the curve takes ToneCurve.SETTING_POINTS", d.find()); continue; }
            assertTrue(key, d.find());
            if (row[2] instanceof Float) assertEquals(key, (Float) row[2], Float.parseFloat(d.group(1)), 1e-6f);
            else assertEquals(key, String.valueOf(row[2]), d.group(1));
        }
        // a stored former default moves, a value chosen by hand stays, once
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",7)
                .putString("pref_scam_hybrid_why_agx_slope","2.70").putString("pref_scam_hybrid_why_cct_sat","1.1")
                .putBoolean("pref_scam_hybrid_why_curve_on",false).putString("pref_scam_hybrid_why_fusion_layout","0")
                .putFloat("pref_scam_hybrid_dezipper",0f).putString("pref_scam_hybrid_why_agx_inset","100")
                .putString("pref_scam_hybrid_why_curve_points",com.particlesdevs.photoncamera.processing.color.ToneCurve.DEFAULT_POINTS).commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("2.45",prefs.getString("pref_scam_hybrid_why_agx_slope",""));
        assertEquals("chosen by hand","1.1",prefs.getString("pref_scam_hybrid_why_cct_sat",""));
        assertTrue(prefs.getBoolean("pref_scam_hybrid_why_curve_on",false));
        assertEquals("1",prefs.getString("pref_scam_hybrid_why_fusion_layout",""));
        assertEquals(4.5f,prefs.getFloat("pref_scam_hybrid_dezipper",0f),0f);
        assertEquals("50",prefs.getString("pref_scam_hybrid_why_agx_inset",""));
        assertEquals(com.particlesdevs.photoncamera.processing.color.ToneCurve.SETTING_POINTS,prefs.getString("pref_scam_hybrid_why_curve_points",""));
        assertEquals(1,prefs.getInt(SettingsMigration.CONFIG_DEFAULTS_REV,0));
        prefs.edit().putBoolean("pref_scam_hybrid_why_curve_on",false).commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertFalse("turned off after the move: stays",prefs.getBoolean("pref_scam_hybrid_why_curve_on",true));
        // P407: P405's matrix saturation 1.25 moves to 1.1 once (a former 1.0 went straight to 1.1 above); set again afterwards it stays
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",7)
                .putInt(SettingsMigration.CONFIG_DEFAULTS_REV,1).putString("pref_scam_hybrid_why_cct_sat","1.25").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("1.1",prefs.getString("pref_scam_hybrid_why_cct_sat",""));
        assertEquals(1,prefs.getInt(SettingsMigration.CCT_SAT_REV,0));
        prefs.edit().putString("pref_scam_hybrid_why_cct_sat","1.25").commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals("set again: stays","1.25",prefs.getString("pref_scam_hybrid_why_cct_sat",""));
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",7)
                .putString("pref_scam_hybrid_why_cct_sat","1").commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertEquals("the former 1.0","1.1",prefs.getString("pref_scam_hybrid_why_cct_sat",""));
    }

    @Test public void cellClipFormerDefaultMovesOffOnce() {
        // P394: a stored «Клип ячейки 2×2 целиком» on (the former XML default) moves off once; turned on after the move it stays
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",7)
                .putInt(SettingsMigration.P68_DEFAULTS_REV,1).putInt(SettingsMigration.DETAIL_DEFAULTS_REV,1)
                .putBoolean("pref_scam_hybrid_cell_clip",true).commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertFalse(prefs.getBoolean("pref_scam_hybrid_cell_clip",true));
        assertEquals(1,prefs.getInt(SettingsMigration.CELL_CLIP_REV,0));
        assertTrue(PreferenceKeys.hybridTuningText().contains("cellClip 0\n"));
        prefs.edit().putBoolean("pref_scam_hybrid_cell_clip",true).commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertTrue(prefs.getBoolean("pref_scam_hybrid_cell_clip",false));
    }

    @Test public void detailDefaultsMoveOnce() {
        // P330: a stored former rho_r default (6) moves once to 2; the Sabre 6.1 kernel stays as stored («Всегда» is the default)
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",7)
                .putInt(SettingsMigration.P68_DEFAULTS_REV,1).putString("pref_scam_hybrid_sabre61","1")
                .putString("pref_scam_hybrid_dn_model_read","6.00").commit();
        assertTrue(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("2",prefs.getString("pref_scam_hybrid_dn_model_read",""));
        assertEquals("1",prefs.getString("pref_scam_hybrid_sabre61",""));
        assertEquals(1,prefs.getInt(SettingsMigration.DETAIL_DEFAULTS_REV,0));
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        // chosen after the move: stays
        prefs.edit().putString("pref_scam_hybrid_dn_model_read","6").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("6",prefs.getString("pref_scam_hybrid_dn_model_read",""));
        // chosen before (not the former default): stays, the marker is set anyway
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putInt("pref_scam_hybrid_defaults_rev",7)
                .putInt(SettingsMigration.P68_DEFAULTS_REV,1).putString("pref_scam_hybrid_dn_model_read","4").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("4",prefs.getString("pref_scam_hybrid_dn_model_read",""));
        assertEquals(1,prefs.getInt(SettingsMigration.DETAIL_DEFAULTS_REV,0));
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
    }

    /**
     * P195 (owner, 2026-10-17: «настройка должна находиться в группе настроек "Мозаика без ремозаика" В самом верху»): «Наш ремозаик»
     * is the first row of the mosaic page, on by default; P267: off is the merged mosaic DNG alone (ScamHybridBurst.dngMosaic), the
     * worker is never told to bin (mosaicRemosaic 0) and every mosaic merge row stays live (that merge is the DNG).
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
        assertFalse(tuning,tuning.contains("mosaicRemosaic"));
        Map<String,Object> values=new HashMap<>();
        values.put("pref_scam_hybrid_mosaic_remosaic",false);
        SettingsAvailability rules=new SettingsAvailability(values).mosaicStreams(true);
        assertNull(rules.reason("pref_scam_hybrid_mosaic_remosaic"));
        assertNull(rules.reason("pref_scam_hybrid_mosaic_frames"));
        assertNull(rules.reason("pref_scam_hybrid_mosaic_path"));
        assertNull(rules.reason("pref_scam_hybrid_mosaic_window"));
        values.put("pref_scam_hybrid_mosaic_remosaic",true);
        assertNull(new SettingsAvailability(values).mosaicStreams(true).reason("pref_scam_hybrid_mosaic_path"));
    }
}
