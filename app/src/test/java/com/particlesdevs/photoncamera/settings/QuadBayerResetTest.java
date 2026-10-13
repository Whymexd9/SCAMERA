package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * P118: Quad Bayer at full resolution and «Quad Bayer — совместимость» are gone: their keys leave every stored profile (main,
 * a module, a restored config) through SettingsMigration.removeObsolete, other settings stay.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class QuadBayerResetTest {
    @Test
    public void storedQuadKeysAreDropped() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences module = context.getSharedPreferences("module_profile_v2_test", Context.MODE_PRIVATE);
        module.edit().clear().putBoolean("pref_quad_bayer_key", true).putBoolean("scamera_quad_bayer_enabled", true)
                .putString("scamera_quad_bayer_mode", "direct_quad").putString("scamera_quad_dng_metadata", "qbcfa_4x4")
                .putString("pref_scam_hybrid_output", "12").commit();
        assertTrue(SettingsMigration.removeObsolete(module));
        for (String key : new String[]{"pref_quad_bayer_key", "scamera_quad_bayer_enabled", "scamera_quad_bayer_mode",
                "scamera_quad_dng_metadata"})
            assertFalse(key, module.contains(key));
        assertTrue("other settings stay", "12".equals(module.getString("pref_scam_hybrid_output", null)));
        assertFalse("a second run changes nothing", SettingsMigration.removeObsolete(module));
    }
}
