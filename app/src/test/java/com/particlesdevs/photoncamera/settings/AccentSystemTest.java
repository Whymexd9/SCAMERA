package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** P130: the accent «Из системы» - the phone's own accent, the first choice and the default. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class AccentSystemTest {
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        android.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
    }

    @Test public void systemIsTheFirstChoiceAndTheDefault() {
        assertEquals(AccentPalette.SYSTEM, AccentPalette.VALUES[0]);
        assertEquals(AccentPalette.VALUES.length, AccentPalette.NAMES.length);
        assertEquals("From system", context.getString(AccentPalette.NAMES[0]));
        final int system = context.getResources().getColor(android.R.color.system_accent1_200, null);
        assertNotEquals(0, AccentPalette.systemColor(context));
        assertEquals(system, AccentPalette.systemColor(context));
        assertTrue("nothing chosen yet follows the phone", AccentPalette.isSystem(AccentPalette.selected(context)));
        assertEquals(system, AccentPalette.color(context));
        assertEquals("the camera controls too", system, AccentPalette.camera(context));
    }

    @Test public void aChosenAccentStaysAndSystemComesBack() {
        AccentPalette.select(context, "blue");
        assertEquals(0xFF90C7FF, AccentPalette.color(context));
        assertEquals(0xFF90C7FF, AccentPalette.camera(context));
        AccentPalette.select(context, AccentPalette.SYSTEM);
        assertEquals(AccentPalette.systemColor(context), AccentPalette.color(context));
        assertEquals(AccentPalette.systemColor(context), AccentPalette.swatch(context, AccentPalette.SYSTEM));
        assertEquals(0xFFC7B5FF, AccentPalette.swatch(context, "lavender"));
    }
}
