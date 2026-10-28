package com.particlesdevs.photoncamera.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Range;
import android.util.SizeF;

import com.google.gson.Gson;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Owner's log 2026-10-28 (vivo V2562): a user gave the 2.4x module Camera ID 9 - the tele's profile for the telephoto
 * extender, a service camera the automatic list leaves out - and it must work (owner: «нам наоборот надо чтобы служебные
 * камеры работали»); adding it as a user lens recomputed every zoom against the first lens of a map loaded in any order
 * (the 0.4x ultra-wide: main 2.5x, tele 6.1x).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CameraAuxModuleTest {
    private static CameraLensData lens(String id, int facing, float focal, float mm35, float zoom) {
        CameraLensData d = new CameraLensData(id);
        d.setFacing(facing);
        d.setCameraFocalLength(focal);
        d.setCameraAperture(2f);
        d.setFlashSupported(facing == 1);
        d.setCamera35mmFocalLength(mm35);
        d.setZoomFactor(zoom);
        return d;
    }

    private static CameraCharacteristics characteristics(float focal, float sensorWidth, boolean mono) {
        CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.LENS_FACING)).thenReturn(CameraCharacteristics.LENS_FACING_BACK);
        when(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)).thenReturn(new float[]{focal});
        when(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)).thenReturn(new float[]{2.67f});
        when(c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)).thenReturn(new SizeF(sensorWidth, sensorWidth * .75f));
        when(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)).thenReturn(true);
        when(c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)).thenReturn(new Range<>(50, 6400));
        when(c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)).thenReturn(new Range<>(10000L, 1000000000L));
        when(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[]{0, 3});
        if (mono) when(c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)).thenReturn(5);
        return c;
    }

    @Test
    public void aChosenServiceCameraStaysAndTheZoomsStay() throws Exception {
        SettingsManager settings = new SettingsManager(RuntimeEnvironment.getApplication());
        try (MockedStatic<ScameraApp> app = mockStatic(ScameraApp.class)) {
            app.when(ScameraApp::getAppContext).thenReturn(RuntimeEnvironment.getApplication());
            app.when(ScameraApp::getResourcesStatic).thenReturn(RuntimeEnvironment.getApplication().getResources());
            app.when(ScameraApp::getSettingsManagerStatic).thenReturn(settings);
            app.when(() -> ScameraApp.getStringStatic(org.mockito.ArgumentMatchers.anyInt()))
                    .thenAnswer(i -> RuntimeEnvironment.getApplication().getString(i.<Integer>getArgument(0)));
            check(settings);
        }
    }

    private void check(SettingsManager settings) throws Exception {
        final String CAMERAS = PreferenceKeys.Key.CAMERAS_PREFERENCE_FILE_NAME.mValue; // the keys read their names through ScameraApp
        // a saved scan (no rescan): the X200 Ultra's lenses, the ultra-wide first as a saved string set may return them
        Gson gson = new Gson();
        CameraLensData[] scan = {lens("4", 1, 4.03f, 14.516f, .3985f), lens("5", 1, 22.48f, 88.55f, 2.4308f),
                lens("3", 1, 11.56f, 36.4286f, 1f), lens("1", 0, 3.64f, 24.99f, 1f)};
        Set<String> json = new LinkedHashSet<>(), ids = new LinkedHashSet<>();
        for (CameraLensData d : scan) {
            json.add(gson.toJson(d));
            ids.add(d.getCameraId());
        }
        settings.set(CAMERAS, "spoof_signature", "off");
        settings.set(CAMERAS, "aux_revision", "aux4");
        settings.set(CAMERAS, PreferenceKeys.Key.ALL_CAMERA_IDS_KEY.mValue, ids);
        settings.set(CAMERAS, PreferenceKeys.Key.ALL_CAMERA_LENS_KEY.mValue, json);
        SharedPreferences prefs = settings.getDefaultPreferences();
        // the 2.4x module set by hand to 9 (a service camera); 7 another lens found by the lens discovery
        prefs.edit().putString("module_id_back0", "9").putString("module_id_back3", "5")
                .putStringSet("user_camera_ids", new HashSet<>(Arrays.asList("9", "7"))).commit();
        CameraManager cameras = mock(CameraManager.class);
        Map<String, CameraCharacteristics> known = new java.util.HashMap<>();
        known.put("9", characteristics(22.48f, 9.14f, true));
        known.put("5", characteristics(22.48f, 9.14f, false));
        known.put("7", characteristics(50f, 9.14f, false));
        when(cameras.getCameraCharacteristics(anyString())).thenAnswer(i -> {
            CameraCharacteristics c = known.get(i.<String>getArgument(0));
            if (c == null) throw new IllegalArgumentException("no such camera");
            return c;
        });

        Map<String, CameraLensData> map = new CameraManager2(cameras, settings).getCameraLensDataMap();

        assertEquals("the module keeps the service camera chosen for it", "9", prefs.getString("module_id_back0", ""));
        assertEquals("5", prefs.getString("module_id_back3", ""));
        assertTrue("9 stays among the user's lenses", prefs.getStringSet("user_camera_ids", new HashSet<>()).contains("9"));
        CameraLensData service = map.get("9");
        assertNotNull("the chosen service camera is a lens of the switcher", service);
        assertEquals("its zoom against the main lens, as the tele's", 2.4308f, service.getZoomFactor(), 1e-3f);
        assertEquals("the main stays 1x", 1f, map.get("3").getZoomFactor(), 1e-4f);
        assertEquals("the tele stays 2.43x", 2.4308f, map.get("5").getZoomFactor(), 1e-4f);
        assertEquals("the ultra-wide stays 0.4x", .3985f, map.get("4").getZoomFactor(), 1e-4f);
        CameraLensData extra = map.get("7");
        assertNotNull("a real lens of the user's is added", extra);
        assertEquals("against the main lens, not the first of the map", extra.getCamera35mmFocalLength() / 36.4286f,
                extra.getZoomFactor(), 1e-3f);
        Set<String> saved = settings.getStringSet(CAMERAS, PreferenceKeys.Key.ALL_CAMERA_LENS_KEY.mValue, null);
        assertEquals("one saved line per lens", map.size(), saved.size());
        for (String line : saved) {
            CameraLensData d = gson.fromJson(line, CameraLensData.class);
            assertEquals(d.getCameraId() + ": the saved zoom is the current one", map.get(d.getCameraId()).getZoomFactor(), d.getZoomFactor(), 1e-6f);
        }
        assertTrue(map.containsKey("1"));
    }
}
