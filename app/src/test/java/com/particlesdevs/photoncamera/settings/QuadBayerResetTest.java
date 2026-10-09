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
 * Quad Bayer switches the RAW stream to the full sensor mode, which the hybrid's ZSL burst must not: a profile written by an
 * older build (main, a module, a restored config) cannot bring it back on (SettingsMigration.removeObsolete).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class QuadBayerResetTest {
    @Test
    public void storedQuadBayerOnIsTurnedOffAndOffStaysUntouched() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences module = context.getSharedPreferences("module_profile_v2_test", Context.MODE_PRIVATE);
        module.edit().clear().putBoolean("pref_quad_bayer_key", true).putString("pref_scam_hybrid_output", "12").commit();
        assertTrue(SettingsMigration.removeObsolete(module));
        assertFalse(module.getBoolean("pref_quad_bayer_key", true));
        assertTrue("other settings stay", "12".equals(module.getString("pref_scam_hybrid_output", null)));
        assertFalse("a second run changes nothing", SettingsMigration.removeObsolete(module));
        SharedPreferences off = context.getSharedPreferences("module_profile_v2_off", Context.MODE_PRIVATE);
        off.edit().clear().putString("pref_quad_bayer_key", "0").commit();
        assertFalse("stored off (any type) is not rewritten", SettingsMigration.removeObsolete(off));
    }
}
