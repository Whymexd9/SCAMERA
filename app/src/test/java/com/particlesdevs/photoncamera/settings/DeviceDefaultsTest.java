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

    /** P365 (v7, owner 2026-10-27): the Find X7 Ultra streams RAW10 as the reference app does there. */
    @Test public void findX7UltraStreamsRaw10FromVersion7() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PHY110");
        SharedPreferences main = prefs("defaults_x7u");
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertEquals("raw10", main.getString("pref_raw_stream_format", "auto"));
        assertNoScamHdrSet(main);
        assertEquals(DeviceDefaults.VERSION, main.getInt("device_defaults_version", 0));
    }

    /** P365: a Find X7 Ultra on v6 gets RAW10 (also in its module profiles) and keeps every other setting; applied once. */
    @Test public void updateFromVersionSixAddsOnlyRaw10OnTheX7Ultra() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PHY110");
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences meta = prefs("module_profiles_meta");
        meta.edit().putBoolean("exists_back2", true).commit();
        SharedPreferences module = prefs("module_profile_v2_back2");
        SharedPreferences main = prefs("defaults_x7u_v6");
        main.edit().putInt("device_defaults_version", 6).putBoolean("pref_camera_package_spoof_enabled", false)
                .putString("pref_scam_hybrid_why_ccm_sat", "0.8").commit();
        DeviceDefaults.applyOnce(context, main);
        for (SharedPreferences p : new SharedPreferences[]{main, module}) assertEquals("raw10", p.getString("pref_raw_stream_format", ""));
        assertFalse(main.getBoolean("pref_camera_package_spoof_enabled", true));
        assertEquals("0.8", main.getString("pref_scam_hybrid_why_ccm_sat", ""));
        assertEquals(7, main.getInt("device_defaults_version", 0));
        main.edit().putString("pref_raw_stream_format", "auto").commit();
        DeviceDefaults.applyOnce(context, main);
        assertEquals("the user's later choice stays", "auto", main.getString("pref_raw_stream_format", ""));
        meta.edit().clear().commit();
    }

    /** P365: v7 is the X7 Ultra's only: a Find X8 Ultra on v6 gets nothing (its RAW10 came with v2). */
    @Test public void updateFromVersionSixLeavesTheX8UltraAlone() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PKJ110");
        SharedPreferences main = prefs("defaults_x8u_v6");
        main.edit().putInt("device_defaults_version", 6).putString("pref_raw_stream_format", "auto").commit();
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertEquals("auto", main.getString("pref_raw_stream_format", ""));
    }

    /** P365: the X7 Ultra's runtime rules - a RAW reader of 8 (the reference app's there), the light ISP stream; none elsewhere. */
    @Test public void theX7UltraTakesTheReferenceReaderAndTheLightIspStream() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PHY110");
        assertTrue(DeviceDefaults.findX7Ultra());
        assertEquals(8, DeviceDefaults.zslReaderImages());
        assertTrue(DeviceDefaults.ispLightViewfinder());
        ShadowBuild.setModel("PKJ110");
        assertFalse(DeviceDefaults.findX7Ultra());
        assertEquals("no rule: zsl_reader 20, at least 16", 0, DeviceDefaults.zslReaderImages());
        assertFalse(DeviceDefaults.ispLightViewfinder());
        ShadowBuild.setManufacturer("vivo");
        ShadowBuild.setModel("PHY110");
        assertFalse(DeviceDefaults.findX7Ultra());
        assertEquals(0, DeviceDefaults.zslReaderImages());
    }

    private static void assertNoScamHdrSet(SharedPreferences main) {
        for (String key : main.getAll().keySet())
            assertFalse(key, key.startsWith("pref_scamhdr_") || key.startsWith("pref_scamold_"));
    }

    /** The spoof is shared by every lens (ModuleProfiles.isGlobal): main settings only; the WHY saturation reaches the profiles. */
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
            assertEquals("1.1", p.getString("pref_scam_hybrid_why_ccm_sat", ""));
        }
        meta.edit().clear().commit();
    }

    /** Owner 2026-10-08: the X7 Ultra lists 5 cameras with the aweme package on all three spoof methods; WHY saturation 1.1. */
    @Test public void findX7UltraSpoofsAwemeAndRaisesTheOppoMatrixSaturation() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PHY110");
        SharedPreferences main = prefs("defaults_x7u_v3");
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertTrue(main.getBoolean("pref_camera_package_spoof_enabled", false));
        for (String key : new String[]{"pref_oplus_spoof_package_key", "pref_generic_spoof_package_key", "pref_binder_spoof_package_key"})
            assertEquals(key, "com.ss.android.ugc.aweme", main.getString(key, ""));
        assertEquals("1.1", main.getString("pref_scam_hybrid_why_ccm_sat", ""));
        assertEquals(DeviceDefaults.VERSION, main.getInt("device_defaults_version", 0));
    }

    @Test public void updateFromVersionTwoAddsOnlyTheX7UltraEntries() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PHY110");
        SharedPreferences main = prefs("defaults_x7u_update");
        // v2 applied, the user then changed a v1 setting and stored the old saturation default
        main.edit().putInt("device_defaults_version", 2).putString("pref_scamhdr_zsl_frames", "12")
                .putString("pref_scam_hybrid_why_ccm_sat", "0.6").commit();
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertEquals("12", main.getString("pref_scamhdr_zsl_frames", ""));
        assertEquals("1.1", main.getString("pref_scam_hybrid_why_ccm_sat", ""));
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
        assertFalse(main.contains("pref_scam_hybrid_why_ccm_sat"));
    }

    /** Owner 2026-10-10: Redmi Note 11 Pro (viva) 12 N frames, 2 Shasta frames, WHY RL 3 amount 1.5 (v5); also in module profiles. */
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
            assertEquals("1.5", p.getString("pref_scam_hybrid_why_sharp_rl3_amount", ""));
        }
        assertFalse(main.contains("pref_camera_package_spoof_enabled"));
        assertEquals(DeviceDefaults.VERSION, main.getInt("device_defaults_version", 0));
        // applied once: a later choice stays
        main.edit().putString("pref_scam_hybrid_zsl_frames", "20").commit();
        DeviceDefaults.applyOnce(context, main);
        assertEquals("20", main.getString("pref_scam_hybrid_zsl_frames", ""));
        meta.edit().clear().commit();
    }

    /** Owner 2026-10-10 evening: a Redmi Note 11 Pro on the v4 defaults gets RL 3 at 1.5 and keeps its other settings. */
    @Test public void redmiNote11ProV5TakesRl3At15() {
        ShadowBuild.setManufacturer("Xiaomi");
        ShadowBuild.setModel("2201116TG");
        ShadowBuild.setDevice("viva");
        SharedPreferences main = prefs("defaults_note11pro_v4");
        main.edit().putInt("device_defaults_version", 4).putString("pref_scam_hybrid_zsl_frames", "20")
                .putString("pref_scam_hybrid_why_sharp_rl3_amount", "1.2").commit();
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertEquals("1.5", main.getString("pref_scam_hybrid_why_sharp_rl3_amount", ""));
        assertEquals("20", main.getString("pref_scam_hybrid_zsl_frames", ""));
        assertEquals(DeviceDefaults.VERSION, main.getInt("device_defaults_version", 0));
    }

    /** Owner 2026-10-10: the Redmi Note 11 Pro's colour denoise is 20 % stronger in low light only; other phones unchanged. */
    @Test public void redmiNote11ProNightChromaDenoise() {
        ShadowBuild.setManufacturer("Xiaomi");
        ShadowBuild.setDevice("viva");
        assertEquals(1.2f, DeviceDefaults.chromaDenoiseFactor(7746), 0f);
        assertEquals(1f, DeviceDefaults.chromaDenoiseFactor(800), 0f);
        ShadowBuild.setManufacturer("Google");
        ShadowBuild.setDevice("panther");
        assertEquals(1f, DeviceDefaults.chromaDenoiseFactor(7746), 0f);
    }

    /** Owner 2026-10-10: Pixel 7 (panther) WHY RL 3 amount 1.5, nothing else. */
    @Test public void pixel7TakesRl3At15() {
        ShadowBuild.setManufacturer("Google");
        ShadowBuild.setModel("Pixel 7");
        ShadowBuild.setDevice("panther");
        SharedPreferences main = prefs("defaults_pixel7");
        DeviceDefaults.applyOnce(RuntimeEnvironment.getApplication(), main);
        assertEquals("1.5", main.getString("pref_scam_hybrid_why_sharp_rl3_amount", ""));
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

    /** P301: the RAW viewfinder with the photo's tone starts on for flagship SoCs only. */
    @Test
    public void flagshipSocsStartWithTheRawViewfinder() {
        for (String soc : new String[]{"SM8650", "SM8750", "SM8850", "MT6989", "MT6991", "MT6993"}) assertTrue(soc, DeviceDefaults.flagshipSoc(soc));
        for (String soc : new String[]{"SM8550", "SM8250", "SM7550", "MT6983", "MT6781", "", null}) assertFalse(String.valueOf(soc), DeviceDefaults.flagshipSoc(soc));
    }
}
