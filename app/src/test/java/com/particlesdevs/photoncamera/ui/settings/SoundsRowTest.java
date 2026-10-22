package com.particlesdevs.photoncamera.ui.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** P319: the «Звуки» row names the two switches it stands for (shutter, timer). */
public class SoundsRowTest {
    @Test public void theRowFollowsBothSwitches() {
        assertEquals("all", SettingsActivity.SettingsFragment.soundsValue(true, true));
        assertEquals("shutter", SettingsActivity.SettingsFragment.soundsValue(true, false));
        assertEquals("timer", SettingsActivity.SettingsFragment.soundsValue(false, true));
        assertEquals("none", SettingsActivity.SettingsFragment.soundsValue(false, false));
    }
}
