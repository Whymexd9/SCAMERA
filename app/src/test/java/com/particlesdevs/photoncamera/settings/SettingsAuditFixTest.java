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
