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

/**
 * Device defaults: versioned, an update adds only the newer entries (owner 2026-10-06: RAW10 on the Find X8 Ultra). The v1
 * SCAM HDR set of both OPPO phones is gone (owner 2026-10-08: SCAM HDR never runs there), so no pref_scamhdr_ value is written.
 */
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
        assertNoScamHdrSet(main);
        assertEquals(DeviceDefaults.VERSION, main.getInt("device_defaults_version", 0));
    }

    @Test public void updateFromVersionOneAddsOnlyRaw10() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PKJ110");
        SharedPreferences main = prefs("defaults_x8u_update");
        // the user changed a v1 setting after it was applied
        main.edit().putInt("device_defaults_version", 1).putString("pref_scamhdr_zsl_frames", "12").commit();
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertEquals("raw10", main.getString("pref_raw_stream_format", "auto"));
        assertEquals("12", main.getString("pref_scamhdr_zsl_frames", ""));
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
        assertNoScamHdrSet(main);
    }

    private static void assertNoScamHdrSet(SharedPreferences main) {
        for (String key : main.getAll().keySet())
            assertFalse(key, key.startsWith("pref_scamhdr_") || key.startsWith("pref_scamold_"));
    }

    /** The spoof is shared by every lens (ModuleProfiles.isGlobal): main settings only; the ARK saturation reaches the profiles. */
    @Test public void sharedEntriesStayOutOfModuleProfiles() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PHY110");
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences meta = prefs("module_profiles_meta");
        meta.edit().putBoolean("exists_back1", true).putBoolean("baseline", true).commit();
        SharedPreferences module = prefs("module_profile_v2_back1"), baseline = prefs("module_profile_v2_common");
        SharedPreferences main = prefs("defaults_x7u_profiles");
        DeviceDefaults.applyOnce(context, main);
        assertTrue(main.getBoolean("pref_camera_package_spoof_enabled", false));
        for (SharedPreferences p : new SharedPreferences[]{module, baseline}) {
            assertFalse(p.contains("pref_camera_package_spoof_enabled"));
            assertFalse(p.contains("pref_oplus_spoof_package_key"));
            assertEquals("1.1", p.getString("pref_scam_hybrid_ark_ccm_sat", ""));
        }
        meta.edit().clear().commit();
    }

    /** Owner 2026-10-08: the X7 Ultra lists 5 cameras with the aweme package on all three spoof methods; ARK saturation 1.1. */
    @Test public void findX7UltraSpoofsAwemeAndRaisesTheOppoMatrixSaturation() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PHY110");
        SharedPreferences main = prefs("defaults_x7u_v3");
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertTrue(main.getBoolean("pref_camera_package_spoof_enabled", false));
        for (String key : new String[]{"pref_oplus_spoof_package_key", "pref_generic_spoof_package_key", "pref_binder_spoof_package_key"})
            assertEquals(key, "com.ss.android.ugc.aweme", main.getString(key, ""));
        assertEquals("1.1", main.getString("pref_scam_hybrid_ark_ccm_sat", ""));
        assertEquals(DeviceDefaults.VERSION, main.getInt("device_defaults_version", 0));
    }

    @Test public void updateFromVersionTwoAddsOnlyTheX7UltraEntries() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PHY110");
        SharedPreferences main = prefs("defaults_x7u_update");
        // v2 applied, the user then changed a v1 setting and stored the old saturation default
        main.edit().putInt("device_defaults_version", 2).putString("pref_scamhdr_zsl_frames", "12")
                .putString("pref_scam_hybrid_ark_ccm_sat", "0.6").commit();
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertEquals("12", main.getString("pref_scamhdr_zsl_frames", ""));
        assertEquals("1.1", main.getString("pref_scam_hybrid_ark_ccm_sat", ""));
        assertTrue(main.getBoolean("pref_camera_package_spoof_enabled", false));
        // applied once: switching the spoof off afterwards stays off
        main.edit().putBoolean("pref_camera_package_spoof_enabled", false).commit();
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertFalse(main.getBoolean("pref_camera_package_spoof_enabled", true));
    }

    @Test public void findX8UltraGetsNoSpoof() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PKJ110");
        SharedPreferences main = prefs("defaults_x8u_v3");
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertFalse(main.contains("pref_camera_package_spoof_enabled"));
        assertFalse(main.contains("pref_scam_hybrid_ark_ccm_sat"));
    }

    /** Owner 2026-10-10: Redmi Note 11 Pro (viva) 12 N frames, 2 Shasta frames, ARK RL 3 amount 1.2; also in module profiles. */
    @Test public void redmiNote11ProMergesFewerFrames() {
        ShadowBuild.setManufacturer("Xiaomi");
        ShadowBuild.setModel("2201116TG");
        ShadowBuild.setDevice("viva");
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences meta = prefs("module_profiles_meta");
        meta.edit().putBoolean("exists_back3", true).commit();
        SharedPreferences module = prefs("module_profile_v2_back3");
        SharedPreferences main = prefs("defaults_note11pro");
        // the stored former defaults of the hybrid (the screen was opened before) are replaced
        main.edit().putString("pref_scam_hybrid_zsl_frames", "30").putString("pref_scam_hybrid_shasta_frames", "5").commit();
        DeviceDefaults.applyOnce(context, main);
        for (SharedPreferences p : new SharedPreferences[]{main, module}) {
            assertEquals("12", p.getString("pref_scam_hybrid_zsl_frames", ""));
            assertEquals("2", p.getString("pref_scam_hybrid_shasta_frames", ""));
            assertEquals("1.2", p.getString("pref_scam_hybrid_ark_sharp_rl3_amount", ""));
        }
        assertFalse(main.contains("pref_camera_package_spoof_enabled"));
        assertEquals(DeviceDefaults.VERSION, main.getInt("device_defaults_version", 0));
        // applied once: a later choice stays
        main.edit().putString("pref_scam_hybrid_zsl_frames", "20").commit();
        DeviceDefaults.applyOnce(context, main);
        assertEquals("20", main.getString("pref_scam_hybrid_zsl_frames", ""));
        meta.edit().clear().commit();
    }

    /** Owner 2026-10-10: Pixel 7 (panther) ARK RL 3 amount 1.5, nothing else. */
    @Test public void pixel7TakesRl3At15() {
        ShadowBuild.setManufacturer("Google");
        ShadowBuild.setModel("Pixel 7");
        ShadowBuild.setDevice("panther");
        SharedPreferences main = prefs("defaults_pixel7");
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertEquals("1.5", main.getString("pref_scam_hybrid_ark_sharp_rl3_amount", ""));
        assertFalse(main.contains("pref_scam_hybrid_zsl_frames"));
        assertEquals(DeviceDefaults.VERSION, main.getInt("device_defaults_version", 0));
    }

    /** Owner 2026-10-10: Pixel 7 2x (camera 4) takes RL 3 at 1.2 while the setting is the device default; elsewhere unchanged. */
    @Test public void pixel7TwoTimesTakesRl3At12() {
        ShadowBuild.setManufacturer("Google");
        ShadowBuild.setDevice("panther");
        assertEquals(1.2f, DeviceDefaults.rl3Amount(1.5f, "4"), 0f);
        assertEquals(1.5f, DeviceDefaults.rl3Amount(1.5f, "2"), 0f);
        assertEquals(1.8f, DeviceDefaults.rl3Amount(1.8f, "4"), 0f); // the user's own value
        ShadowBuild.setDevice("viva");
        ShadowBuild.setManufacturer("Xiaomi");
        assertEquals(1.5f, DeviceDefaults.rl3Amount(1.5f, "4"), 0f);
    }

    @Test public void otherXiaomiPhonesGetNothing() {
        ShadowBuild.setManufacturer("Xiaomi");
        ShadowBuild.setModel("25128PNA1C");
        ShadowBuild.setDevice("nezha");
        SharedPreferences main = prefs("defaults_17u");
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertTrue(main.getAll().isEmpty());
    }

    @Test public void otherPhonesGetNothing() {
        ShadowBuild.setManufacturer("vivo");
        ShadowBuild.setModel("V2366GA");
        SharedPreferences main = prefs("defaults_scam");
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertTrue(main.getAll().isEmpty());
    }
}
