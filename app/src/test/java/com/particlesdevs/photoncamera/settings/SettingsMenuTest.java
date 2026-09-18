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
@Config(sdk=35, application=Application.class)
public class SettingsMenuTest {
    private Context context;
    private SettingsManager manager;
    private SharedPreferences prefs;
    private MockedStatic<PhotonCamera> camera;
    @Before public void setUp(){
        context=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_Photon_SettingsActivity);
        manager=new SettingsManager(context); prefs=manager.getDefaultPreferences();prefs.edit().clear().commit();
        PreferenceKeys.initialise(manager);
        camera=mockStatic(PhotonCamera.class);
        camera.when(PhotonCamera::getSettingsManagerStatic).thenReturn(manager);
        PhotonCamera app=mock(PhotonCamera.class);when(app.getSettingsManager()).thenReturn(manager);
        camera.when(()->PhotonCamera.getInstance(any(Context.class))).thenReturn(app);
    }
    @After public void tearDown(){camera.close();}
    private PreferenceScreen inflate(){
        SettingsMigration.prepare(context,prefs);
        PreferenceManager pm=new PreferenceManager(context);
        PreferenceScreen screen=pm.inflateFromResource(context,R.xml.preferences,null);pm.setPreferences(screen);
        for(Class<?> type:TunableRegistry.TUNABLE_CLASSES)TunablePreferenceGenerator.registerTunableClass(type);
        TunablePreferenceGenerator.generatePreferences(context,screen);
        return screen;
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
        PreferenceKeys.loadSettingsForCamera("audit");
        assertTrue(prefs.getBoolean("scamera_darktable_enabled",false));
        assertEquals(4.0,PreferenceNumber.read(manager.getString("default_scope","pref_remosaic_block_key","2"),2),0.0);
        assertEquals(37.125,PreferenceNumber.read(manager.getString("default_scope","hexquad_luma","0"),0),0.0);
        assertFalse(prefs.contains("ignored_null"));assertEquals("keep",prefs.getString(PreferenceKeys.Key.KEY_THEME.mValue,""));
    }

}
