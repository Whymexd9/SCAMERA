package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** P241 (speed-ux C6): diagnostics turn themselves off 24 h after the camera first saw them on. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DiagnosticsAutoOffTest {
    private static final long H = 60L * 60 * 1000;

    @Test public void theDecisionCountsFromTheFirstTimeSeenOn() {
        assertArrayEquals("off: nothing kept", new long[]{0, 0}, DiagnosticsAutoOff.decide(false, 5 * H, 100 * H));
        assertArrayEquals("first seen on now", new long[]{100 * H, 0}, DiagnosticsAutoOff.decide(true, 0, 100 * H));
        assertArrayEquals("23 h: stays", new long[]{100 * H, 0}, DiagnosticsAutoOff.decide(true, 100 * H, 123 * H));
        assertArrayEquals("24 h: off", new long[]{0, 1}, DiagnosticsAutoOff.decide(true, 100 * H, 124 * H));
        assertArrayEquals("the clock set back: the 24 h start again", new long[]{90 * H, 0}, DiagnosticsAutoOff.decide(true, 100 * H, 90 * H));
    }

    @Test public void theStoredSwitchGoesOffAfter24Hours() {
        final Context context = RuntimeEnvironment.getApplication();
        final SettingsManager settings = new SettingsManager(context);
        PreferenceKeys.initialise(settings);
        context.getSharedPreferences(DiagnosticsAutoOff.FILE, Context.MODE_PRIVATE).edit().clear().commit();
        settings.set("default_scope", DiagnosticsAutoOff.HYBRID_KEY, true);
        assertTrue(DiagnosticsAutoOff.storedOn());
        assertNull("seen on: the 24 h start", DiagnosticsAutoOff.check(context, 1000 * H));
        assertNull(DiagnosticsAutoOff.check(context, 1020 * H));
        assertTrue(DiagnosticsAutoOff.dumpsOn());
        final String said = DiagnosticsAutoOff.check(context, 1024 * H);
        assertNotNull("24 h later: off, and said", said);
        assertFalse(DiagnosticsAutoOff.storedOn());
        assertFalse(settings.getBoolean("default_scope", DiagnosticsAutoOff.LEGACY_KEY, false));
        assertNull("off: nothing more", DiagnosticsAutoOff.check(context, 1100 * H));
        // switched on again: a new 24 h
        settings.set("default_scope", DiagnosticsAutoOff.LEGACY_KEY, true);
        assertNull(DiagnosticsAutoOff.check(context, 1200 * H));
        assertNull(DiagnosticsAutoOff.check(context, 1223 * H));
        assertNotNull(DiagnosticsAutoOff.check(context, 1224 * H));
    }
}
