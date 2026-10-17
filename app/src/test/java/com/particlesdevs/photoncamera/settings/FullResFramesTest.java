package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P190 (owner, 2026-10-17: «дай возможность ставить столько кадров для 50/200мп сколько я хочу»): «Кадров в снимке 50 Мп»
 * (0..64, 0 = as «Кадров в склейке») and «Кадров в снимке 200 Мп» (3..64, default 4) are per-module values of the FULLRES profiles.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class FullResFramesTest {
    private static final String F50 = FullResProfiles.FRAMES_50, F200 = FullResProfiles.FRAMES_200;
    private Context context;
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        PreferenceKeys.initialise(manager);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
    }

    @After
    public void tearDown() throws Exception {
        devFile("");
        context.getSharedPreferences("module_profiles_meta", Context.MODE_PRIVATE).edit().clear().commit();
        for (String id : new String[]{"back0", "back0@full50", "back0@full200", "common"})
            context.getSharedPreferences("module_profile_v2_" + id, Context.MODE_PRIVATE).edit().clear().commit();
        if (camera != null) camera.close();
    }

    /** scam_dev.txt with these lines (empty: none), re-read at once. */
    private void devFile(String lines) throws Exception {
        // P191: scam_dev.txt lives in Download/SCAMERA now (ScamDevFile)
        File file = ScamDevFile.file(context);
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        if (lines.isEmpty()) Files.deleteIfExists(file.toPath());
        else Files.write(file.toPath(), lines.getBytes(StandardCharsets.UTF_8));
        java.lang.reflect.Field stamp = PreferenceKeys.class.getDeclaredField("scamDevStamp");
        stamp.setAccessible(true);
        stamp.setLong(null, -1);
    }

    private static File src(String path) {
        File f = new File(path);
        return f.exists() ? f : new File("app/" + path);
    }

    @Test
    public void bothKeysArePerModuleWithTheirBoundsAndDefaults() throws Exception {
        for (String key : new String[]{F50, F200}) {
            assertTrue(key, ModuleProfiles.isLocal(key));
            assertFalse(key, ModuleProfiles.isGlobal(key));
            assertFalse("an explicit count acts in «Движение» too", MotionMode.replaces(key.substring(PreferenceKeys.HYBRID_PREFIX.length())));
        }
        assertArrayEquals(new double[]{0, 64, 1}, SettingsNumericRules.bounds(F50), 0);
        assertArrayEquals(new double[]{3, 64, 1}, SettingsNumericRules.bounds(F200), 0);
        // the XML rows: the same bounds, defaults 0 and 4 (64 is the visible hard limit)
        String xml = new String(Files.readAllBytes(src("src/main/res/xml/preferences.xml").toPath()), StandardCharsets.UTF_8);
        for (String[] row : new String[][]{{F50, "0", "0"}, {F200, "4", "3"}}) {
            Matcher m = Pattern.compile("<[^<]*android:key=\"" + row[0] + "\"[^>]*/>").matcher(xml);
            assertTrue(row[0], m.find());
            String line = m.group();
            assertTrue(line, line.contains("android:defaultValue=\"" + row[1] + "\""));
            assertTrue(line, line.contains("app:minValue=\"" + row[2] + "\""));
            assertTrue(line, line.contains("app:maxValue=\"64\""));
        }
        assertEquals(0, PreferenceKeys.getFullRes50Frames());
        assertEquals(4, PreferenceKeys.getFullRes200Frames());
    }

    @Test
    public void autoFollowsTheMergesFramesAndAnExplicitCountIgnoresMotion() {
        prefs.edit().putString("pref_scam_hybrid_zsl_frames", "30").commit();
        assertEquals("auto", 0, PreferenceKeys.getFullRes50Frames());
        assertEquals("auto: the merge's frames", 30, PreferenceKeys.getHybridZslFrames());
        PreferenceKeys.setMotionMode(true);
        try {
            prefs.edit().putString("pref_scam_hybrid_mm_zsl_frames", "8").commit();
            assertEquals("auto in «Движение»: the mode's frames", 8, PreferenceKeys.getHybridZslFrames());
            assertEquals(0, PreferenceKeys.getFullRes50Frames());
            prefs.edit().putString(F50, "25").putString(F200, "9").commit();
            assertEquals(25, PreferenceKeys.getFullRes50Frames());
            assertEquals(9, PreferenceKeys.getFullRes200Frames());
        } finally {
            PreferenceKeys.setMotionMode(false);
        }
        assertEquals(25, PreferenceKeys.getFullRes50Frames());
        prefs.edit().putString(F50, "99").putString(F200, "1").commit();
        assertEquals("stored values clamped", 64, PreferenceKeys.getFullRes50Frames());
        assertEquals(3, PreferenceKeys.getFullRes200Frames());
    }

    @Test
    public void aDevOverrideIsClampedToTheHardLimits() throws Exception {
        devFile("hybrid_fullres50_frames 100\nhybrid_fullres200_frames 2\n");
        assertEquals(64, PreferenceKeys.getFullRes50Frames());
        assertEquals(3, PreferenceKeys.getFullRes200Frames());
        devFile("hybrid_fullres50_frames -5\nhybrid_fullres200_frames 70\n");
        assertEquals(0, PreferenceKeys.getFullRes50Frames());
        assertEquals(64, PreferenceKeys.getFullRes200Frames());
    }

    /** The 50 and 200 MP profiles of a module keep their own counts; with per-lens settings off the two keys stay apart. */
    @Test
    public void fiftyAndTwoHundredKeepSeparateValues() {
        prefs.edit().putString(F50, "12").putString(F200, "6").commit();
        assertEquals(12, PreferenceKeys.getFullRes50Frames());
        assertEquals("per-lens off: two separate keys", 6, PreferenceKeys.getFullRes200Frames());
        prefs.edit().putString("module_auto_back0", "3").putString("module_label_back0", "1×").putBoolean("module_visible_back0", true)
                .putString("module_active", "back0").commit();
        String perLens = PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue;
        prefs.edit().putBoolean(perLens, true).commit();
        PreferenceKeys.profiles().changed(perLens);
        PreferenceKeys.profiles().copy("back0", Collections.singletonList("back0@full50"), Collections.singleton(F50));
        prefs.edit().putString(F200, "10").commit();
        PreferenceKeys.profiles().changed(F200);
        PreferenceKeys.profiles().copy("back0", Arrays.asList("back0@full200"), Collections.singleton(F200));
        assertEquals("12", String.valueOf(PreferenceKeys.profiles().snapshot("back0@full50").get(F50)));
        assertEquals("10", String.valueOf(PreferenceKeys.profiles().snapshot("back0@full200").get(F200)));
        assertEquals("the 50 MP profile's 200 MP row untouched", "6", String.valueOf(PreferenceKeys.profiles().snapshot("back0@full50").get(F200)));
    }

    /** P189 module group: the 50 row in a 50 MP profile only, the 200 row in a 200 MP one only, neither in a module's own. */
    @Test
    public void eachRowActsInItsOwnFullResProfileOnly() {
        assertEquals("50", FullResProfiles.framesTag(F50));
        assertEquals("200", FullResProfiles.framesTag(F200));
        assertNull(FullResProfiles.framesTag("pref_scam_hybrid_zsl_frames"));
        assertTrue(FullResProfiles.framesRowApplies(F50, "back2@full50"));
        assertFalse(FullResProfiles.framesRowApplies(F50, "back2@full200"));
        assertFalse(FullResProfiles.framesRowApplies(F50, "back2"));
        assertTrue(FullResProfiles.framesRowApplies(F200, "back2@full200"));
        assertFalse(FullResProfiles.framesRowApplies(F200, "back2@full50"));
        assertFalse(FullResProfiles.framesRowApplies(F200, "back0@full"));
        assertTrue("other rows: unchanged", FullResProfiles.framesRowApplies("pref_scam_hybrid_cdm", "back2"));
        // no FULLRES on this (test) phone: the rows are hidden on the usual pages
        assertFalse(FullResProfiles.sizeOffered("50"));
        assertFalse(FullResProfiles.sizeOffered("200"));
        SettingsAvailability none = new SettingsAvailability(Collections.emptyMap()).fullResSizes(false, false);
        assertTrue(none.hidden(F50));
        assertTrue(none.hidden(F200));
        SettingsAvailability fifty = new SettingsAvailability(Collections.emptyMap()).fullResSizes(true, false);
        assertFalse(fifty.hidden(F50));
        assertTrue(fifty.hidden(F200));
        assertFalse("assumed unless told otherwise", new SettingsAvailability(Collections.emptyMap()).hidden(F200));
    }
}
