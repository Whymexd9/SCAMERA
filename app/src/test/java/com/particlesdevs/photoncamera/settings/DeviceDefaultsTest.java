package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;
import static org.junit.Assert.*;

/** Device defaults: versioned, an update adds only the newer entries (owner 2026-10-06: RAW10 on the Find X8 Ultra). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DeviceDefaultsTest {
    private static SharedPreferences prefs(String name) {
        SharedPreferences p = RuntimeEnvironment.getApplication().getSharedPreferences(name, Context.MODE_PRIVATE);
        p.edit().clear().commit();
        return p;
    }

    @Test public void findX8UltraStreamsRaw10ByDefault() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PKJ110");
        SharedPreferences main = prefs("defaults_x8u");
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertEquals("raw10", main.getString("pref_raw_stream_format", "auto"));
        assertEquals("scamera", main.getString("pref_vivo_nice_planner", ""));
        assertEquals(DeviceDefaults.VERSION, main.getInt("device_defaults_version", 0));
    }

    @Test public void updateFromVersionOneAddsOnlyRaw10() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PKJ110");
        SharedPreferences main = prefs("defaults_x8u_update");
        // the user changed a v1 setting after it was applied
        main.edit().putInt("device_defaults_version", 1).putString("pref_vivo_nice_zsl_frames", "12").commit();
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertEquals("raw10", main.getString("pref_raw_stream_format", "auto"));
        assertEquals("12", main.getString("pref_vivo_nice_zsl_frames", ""));
        // applied once: a later choice of the user stays
        main.edit().putString("pref_raw_stream_format", "auto").commit();
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertEquals("auto", main.getString("pref_raw_stream_format", ""));
    }

    @Test public void findX7UltraKeepsTheAutoRawFormat() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PHY110");
        SharedPreferences main = prefs("defaults_x7u");
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertFalse(main.contains("pref_raw_stream_format"));
        assertEquals("scamera", main.getString("pref_vivo_nice_planner", ""));
    }

    @Test public void otherPhonesGetNothing() {
        ShadowBuild.setManufacturer("vivo");
        ShadowBuild.setModel("V2366GA");
        SharedPreferences main = prefs("defaults_vivo");
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertTrue(main.getAll().isEmpty());
    }
}
