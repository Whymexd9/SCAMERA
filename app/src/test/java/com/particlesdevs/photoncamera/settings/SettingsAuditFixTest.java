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
}
