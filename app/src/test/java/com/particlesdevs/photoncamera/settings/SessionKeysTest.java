package com.particlesdevs.photoncamera.settings;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraManager;
import android.view.ContextThemeWrapper;

import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.api.CameraEventsListener;
import com.particlesdevs.photoncamera.api.CameraManager2;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.annotations.SensorConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P214g (speed-ux C5, P211): the settings a camera session reads when it is built - the restart list of the shade, of the way
 * back from the settings and of the «↻» rows - from the @SensorConfig fields of the session, the module's vendor keys and the
 * hand-written keys.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class SessionKeysTest {
    private Context context;
    private SettingsManager manager;
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;

    @Before
    public void setUp() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        camera.when(ScameraApp::getSettings).thenReturn(new com.particlesdevs.photoncamera.api.Settings());
        ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
    }

    @After
    public void tearDown() {
        if (camera != null) camera.close();
    }

    /** The @SensorConfig fields of a class, in lower case as their preference keys end. */
    private static Set<String> sensorFields(Class<?> c) {
        Set<String> out = new TreeSet<>();
        for (Field f : c.getDeclaredFields()) if (f.isAnnotationPresent(SensorConfig.class)) out.add(f.getName().toLowerCase(Locale.ROOT));
        return out;
    }

    @Test
    public void theSessionsSensorConfigFieldsTakeANewSession() {
        // P211: «Тип сессии камеры» goes into the session's operation mode, «Оптическая стабилизация» into the viewfinder request
        assertTrue(SessionKeys.needsNewSession("pref_sensorconfig_back0_sessiontype"));
        assertTrue(SessionKeys.needsNewSession("pref_sensorconfig_back0_oismode"));
        Set<String> fields = sensorFields(CaptureController.class);
        assertTrue(fields.containsAll(Arrays.asList("sessiontype", "oismode")));
        assertEquals("derived from the annotations, not written by hand", fields, SessionKeys.sessionFields());
        // any module scope: the slots and the legacy physical ids of ModuleSensorSettings.runtimeScope
        for (String field : fields)
            for (String scope : new String[]{"back0", "back7", "front1", "3"})
                assertTrue(scope + " " + field, SessionKeys.needsNewSession(SessionKeys.SENSOR_PREFIX + scope + "_" + field));
    }

    @Test
    public void theSensorConfigOfEachShotDoesNot() {
        Set<String> perShot = sensorFields(Parameters.class);
        assertTrue(perShot.containsAll(Arrays.asList("blackleveloverride", "whiteleveloverride")));
        for (String field : perShot) assertFalse(field, SessionKeys.needsNewSession("pref_sensorconfig_back0_" + field));
    }

    /** A new @SensorConfig class has to be placed: read when the session is built, or for each shot. */
    @Test
    public void everySensorConfigClassIsPlaced() {
        Set<Class<?>> session = new HashSet<>(SessionKeys.SESSION_CLASSES), shot = new HashSet<>(SessionKeys.PER_SHOT_CLASSES);
        assertTrue("one place each", Collections.disjoint(session, shot));
        Set<Class<?>> registry = new HashSet<>(Arrays.asList(SensorConfigRegistry.SENSOR_CONFIG_CLASSES));
        for (Class<?> c : registry) assertTrue(c.getName(), session.contains(c) || shot.contains(c));
        for (Class<?> c : session) assertTrue(c.getName(), registry.contains(c) && !sensorFields(c).isEmpty());
        for (Class<?> c : shot) assertTrue(c.getName(), registry.contains(c) && !sensorFields(c).isEmpty());
    }

    @Test
    public void theModulesVendorKeysTakeANewSession() {
        assertTrue(SessionKeys.needsNewSession("pref_sensorconfig_back1_tunablekeys"));
        assertTrue(SessionKeys.needsNewSession("pref_sensorconfig_2_tunablekeys"));
        assertFalse("the «Добавить vendor tag» row stores nothing", SessionKeys.needsNewSession("pref_sensorconfig_back1_add_tunablekey"));
        assertFalse("no module scope", SessionKeys.needsNewSession("pref_sensorconfig__sessiontype"));
        assertFalse(SessionKeys.needsNewSession("pref_sensorconfig_back0_"));
        assertFalse(SessionKeys.needsNewSession("pref_sensorconfig_sessiontype"));
        assertFalse(SessionKeys.needsNewSession("module_sensor_migrated_back0"));
        assertFalse(SessionKeys.needsNewSession(null));
        assertFalse(SessionKeys.needsNewSession(""));
    }

    @Test
    public void theHandWrittenKeysAreTheShadesAndWhatTheBuildReads() {
        // the shade's list of owner's answer 7
        for (String key : new String[]{"pref_live_viewfinder_raw_key", "pref_wide169_key", "pref_raw_stream_format",
                "pref_zsl_buffer_count_key", "pref_af_mode_key"}) {
            assertTrue(key, SessionKeys.needsNewSession(key));
            assertTrue(key, ShadeCatalog.SESSION_KEYS.contains(key));
        }
        // read by the build too: «Движение» (session keys, AE frame rate), the low-light boost AE mode, the FULLRES profile, EIS
        for (String key : new String[]{MotionMode.PREF, com.particlesdevs.photoncamera.capture.HiddenFeatures.PREF_LOW_LIGHT_BOOST,
                com.particlesdevs.photoncamera.capture.VivoSdkFullRes.PREF_HW_REMOSAIC, "pref_eis_photo_key"})
            assertTrue(key, SessionKeys.needsNewSession(key));
        // read again by the running session or by each shot: no restart
        for (String key : new String[]{"pref_show_grid_key", "pref_face_detect_mode", "pref_tracking_af_mode",
                "pref_xiaomi_smooth_zoom", "pref_scam_hybrid_zsl_frames", "pref_scam_hybrid_bento", "pref_antibanding_hz_key",
                "pref_save_raw_key", "pref_preview_format_key"})
            assertFalse(key, SessionKeys.needsNewSession(key));
        // every hand-written key is a real setting: a row of the settings tree, or EIS (its row went in P25, the key stays)
        SettingsMigration.prepare(context, prefs);
        PreferenceManager pm = new PreferenceManager(context);
        PreferenceScreen tree = pm.inflateFromResource(context, R.xml.preferences, null);
        assertEquals("pref_eis_photo_key", context.getString(R.string.pref_eis_photo_key));
        for (String key : SessionKeys.PLAIN)
            assertTrue(key, tree.findPreference(key) != null || key.equals(context.getString(R.string.pref_eis_photo_key)));
    }

    @Test
    public void theShadesRowsFollowTheList() {
        // as ShadeCatalogTest: the settings cache of a mock (Settings.loadCache loads the native Allocator)
        camera.when(ScameraApp::getSettings).thenReturn(mock(com.particlesdevs.photoncamera.api.Settings.class));
        PreferenceKeys.setDefaults(context);
        ShadeCatalog.setFlashAvailable(true);
        ShadeCatalog catalog = new ShadeCatalog(context, prefs);
        assertTrue(catalog.entry("pref_live_viewfinder_raw_key").sessionTime);
        assertTrue("P86: the top-bar switch restarts for it, the shade did not", catalog.entry(MotionMode.PREF).sessionTime);
        assertTrue(catalog.entry(com.particlesdevs.photoncamera.capture.HiddenFeatures.PREF_LOW_LIGHT_BOOST).sessionTime);
        assertFalse(catalog.entry("pref_show_grid_key").sessionTime);
        assertFalse(catalog.entry("pref_scam_hybrid_zsl_frames").sessionTime);
        assertEquals("16:9 " + SessionKeys.MARK, SessionKeys.marked("16:9").toString());
        assertEquals(SessionKeys.MARK, SessionKeys.marked(null).toString());
    }

    private static String vendorJson(String type, String value, boolean enabled, boolean tested, boolean supported) {
        return "[{\"type\":\"" + type + "\",\"name\":\"vivo.control.forceSensorMode\",\"valueType\":\"Integer\",\"value\":\""
                + value + "\",\"supported\":" + supported + ",\"tested\":" + tested + ",\"enabled\":" + enabled + "}]";
    }

    @Test
    public void aSessionCountsItsOwnModuleAndWhatTheVendorKeysSend() {
        final String vendor = "pref_sensorconfig_back0_tunablekeys";
        Map<String, Object> all = new HashMap<>();
        all.put("pref_wide169_key", true);
        all.put("pref_sensorconfig_back0_sessiontype", "32777");
        all.put("pref_sensorconfig_back0_oismode", "1");
        all.put("pref_sensorconfig_back0_blackleveloverride", "64");
        all.put("pref_sensorconfig_back1_sessiontype", "0");
        all.put("pref_show_grid_key", "1");
        all.put(vendor, vendorJson("Session", "5", true, false, false));
        Map<String, String> built = SessionKeys.snapshot(all, "back0");
        assertEquals(new TreeSet<>(Arrays.asList("pref_wide169_key", "pref_sensorconfig_back0_sessiontype",
                "pref_sensorconfig_back0_oismode", vendor)), built.keySet());
        // the camera writes the tested / supported flags of the list back each time a session applies it: no change
        all.put(vendor, vendorJson("Session", "5", true, true, true));
        assertEquals(Collections.emptyList(), SessionKeys.changed(built, SessionKeys.snapshot(all, "back0")));
        // another module, a per-shot level, a live setting: no change either
        all.put("pref_sensorconfig_back1_sessiontype", "32777");
        all.put("pref_sensorconfig_back0_blackleveloverride", "70");
        all.put("pref_show_grid_key", "2");
        assertEquals(Collections.emptyList(), SessionKeys.changed(built, SessionKeys.snapshot(all, "back0")));
        // the module's session type, a vendor key's value, a removed 16:9: listed, sorted
        all.put("pref_sensorconfig_back0_sessiontype", "0");
        all.put(vendor, vendorJson("Session", "7", true, true, true));
        all.remove("pref_wide169_key");
        assertEquals(Arrays.asList("pref_sensorconfig_back0_sessiontype", vendor, "pref_wide169_key"),
                SessionKeys.changed(built, SessionKeys.snapshot(all, "back0")));
        // what is sent: a key switched off or a result key is not; a list that does not parse counts as its text
        assertEquals("", SessionKeys.vendorContent(vendorJson("Session", "7", false, true, true)));
        assertEquals("", SessionKeys.vendorContent(vendorJson("CaptureResult", "7", true, true, true)));
        assertEquals("CaptureRequest|vivo.control.forceSensorMode|Integer|7;",
                SessionKeys.vendorContent(vendorJson("CaptureRequest", "7", true, false, false)));
        assertEquals("not json", SessionKeys.vendorContent("not json"));
        assertEquals(Collections.emptyMap(), SessionKeys.snapshot(null, "back0"));
        assertEquals(Collections.singletonList("pref_wide169_key"),
                SessionKeys.changed(null, Collections.singletonMap("pref_wide169_key", "true")));
    }

    /** CaptureController compares the settings now with the ones its last session was built with. */
    @Test
    public void theControllerComparesWithItsLastSession() throws Exception {
        prefs.edit().putString("module_auto_back0", "3").putBoolean("module_visible_back0", true)
                .putString("module_active", "back0").commit();
        try (MockedConstruction<CameraManager2> managers = mockConstruction(CameraManager2.class,
                (mock, c) -> when(mock.getCameraIdList()).thenReturn(new String[]{"3"}))) {
            Activity activity = mock(Activity.class);
            when(activity.getSystemService(Context.CAMERA_SERVICE)).thenReturn(mock(CameraManager.class));
            CaptureController controller = new CaptureController(activity, mock(ExecutorService.class), mock(CameraEventsListener.class));
            assertEquals("no session built yet", Collections.emptyList(), controller.sessionSettingsChanged());
            assertFalse(controller.sessionRunning());
            Field id = CaptureController.class.getDeclaredField("physicalID");
            id.setAccessible(true);
            id.set(controller, "3");
            prefs.edit().putString("pref_sensorconfig_back0_sessiontype", "0")
                    .putString("pref_sensorconfig_back0_tunablekeys", vendorJson("Session", "5", true, false, false)).commit();
            Method record = CaptureController.class.getDeclaredMethod("recordSessionSettings");
            record.setAccessible(true);
            record.invoke(controller);
            assertEquals(Collections.emptyList(), controller.sessionSettingsChanged());
            // the session's own write-back of the vendor key flags (TunableKeyManager.applyTunableKeys) is no change
            prefs.edit().putString("pref_sensorconfig_back0_tunablekeys", vendorJson("Session", "5", true, true, true)).commit();
            assertEquals(Collections.emptyList(), controller.sessionSettingsChanged());
            // another module's session type is not this session's
            prefs.edit().putString("pref_sensorconfig_back1_sessiontype", "32777").commit();
            assertEquals(Collections.emptyList(), controller.sessionSettingsChanged());
            prefs.edit().putString("pref_sensorconfig_back0_sessiontype", "32777").putString("pref_sensorconfig_back0_oismode", "2")
                    .putBoolean("pref_wide169_key", true).commit();
            List<String> changed = controller.sessionSettingsChanged();
            assertEquals(Arrays.asList("pref_sensorconfig_back0_oismode", "pref_sensorconfig_back0_sessiontype", "pref_wide169_key"), changed);
            // the next session is built with them
            record.invoke(controller);
            assertEquals(Collections.emptyList(), controller.sessionSettingsChanged());
        }
    }
}
