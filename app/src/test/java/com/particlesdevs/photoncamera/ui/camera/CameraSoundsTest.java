package com.particlesdevs.photoncamera.ui.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class CameraSoundsTest {
    @Test
    public void shutterFollowsItsSwitchAndTheRingerUnlessTheRegionForcesIt() {
        assertTrue(CameraSounds.shutterAudible(true, false, true));
        assertFalse("switch off", CameraSounds.shutterAudible(false, false, true));
        assertFalse("silent / vibrate", CameraSounds.shutterAudible(true, false, false));
        assertTrue("region rule, switch off", CameraSounds.shutterAudible(false, true, true));
        assertTrue("region rule, silent mode", CameraSounds.shutterAudible(false, true, false));
    }

    @Test
    public void timerSoundIsIndependentOfTheShutterSwitch() {
        assertTrue(CameraSounds.timerAudible(true, true));
        assertFalse(CameraSounds.timerAudible(false, true));
        assertFalse(CameraSounds.timerAudible(true, false));
    }

    @Test
    public void bothSwitchesAreInTheSettingsAndTheOldBurstSoundsAreGone() throws Exception {
        File xml = new File("src/main/res/xml/preferences.xml");
        String s = new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8);
        assertTrue(s.contains("@string/pref_camera_sounds_key") && s.contains("Звук затвора"));
        assertTrue(s.contains("@string/pref_timer_sound_key") && s.contains("Звук таймера"));
        assertTrue(new File("src/main/res/raw/sound_shutter.mp3").isFile());
        assertTrue(new File("src/main/res/raw/sound_timer.mp3").isFile());
        assertFalse(new File("src/main/res/raw/sound_burst2.wav").exists());
        assertFalse(new File("src/main/res/raw/sound_end.wav").exists());
        assertEquals(1, s.split("pref_timer_sound_key", -1).length - 1);
    }
}
