package com.particlesdevs.photoncamera.ui.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CameraSoundsTest {
    /** The text of string {@code name} in {@code file} (res/values*), or null. */
    private static String string(String file, String name) throws Exception {
        String xml = new String(Files.readAllBytes(new File(file).toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<string name=\"" + Pattern.quote(name) + "\"[^>]*>([^<]*)</string>").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    /** The title resource of the preference with {@code key} in preferences.xml, or null. */
    private static String titleOf(String preferences, String key) {
        for (String element : preferences.split("<")) {
            if (!element.contains("android:key=\"" + key + "\"")) continue;
            Matcher m = Pattern.compile("android:title=\"@string/([A-Za-z0-9_]+)\"").matcher(element);
            return m.find() ? m.group(1) : null;
        }
        return null;
    }

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

    @Test public void synthesizedSoundsOnlyOnPd2454WithTheActualCtsPackage() {
        assertTrue(CameraSounds.synthesizedFor(true, "android.camera.cts"));
        assertFalse(CameraSounds.synthesizedFor(false, "android.camera.cts"));
        assertFalse(CameraSounds.synthesizedFor(true, "org.codeaurora.snapcam"));
        assertFalse(CameraSounds.synthesizedFor(true, "android.camera.cts.extra"));
        assertFalse(CameraSounds.synthesizedFor(true, null));
    }

    @Test
    public void bothSwitchesAreInTheSettingsAndTheOldBurstSoundsAreGone() throws Exception {
        File xml = new File("src/main/res/xml/preferences.xml");
        String s = new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8);
        // the titles are resources: Russian in values-ru, English in values (i18n)
        String shutter = titleOf(s, "@string/pref_camera_sounds_key"), timer = titleOf(s, "@string/pref_timer_sound_key");
        assertEquals("Звук затвора", string("src/main/res/values-ru/strings_settings.xml", shutter));
        assertEquals("Звук таймера", string("src/main/res/values-ru/strings_settings.xml", timer));
        assertEquals("Shutter sound", string("src/main/res/values/strings_settings.xml", shutter));
        assertEquals("Timer sound", string("src/main/res/values/strings_settings.xml", timer));
        assertTrue(new File("src/main/res/raw/sound_shutter.mp3").isFile());
        assertTrue(new File("src/main/res/raw/sound_timer.mp3").isFile());
        assertFalse(new File("src/main/res/raw/sound_burst2.wav").exists());
        assertFalse(new File("src/main/res/raw/sound_end.wav").exists());
        assertEquals(1, s.split("pref_timer_sound_key", -1).length - 1);
    }

    /**
     * P247: where MP3 cannot be decoded (the X200 Ultra's CTS package) the same two sounds play from PCM - 16-bit mono at
     * PCM_RATE, the shutter's 0.13 s and the timer's 10.7 s countdown - so both switches work there too.
     */
    @Test public void thePcmCopiesOfBothSounds() throws Exception {
        byte[] shutter = Files.readAllBytes(new File("src/main/res/raw/sound_shutter_pcm.raw").toPath());
        byte[] timer = Files.readAllBytes(new File("src/main/res/raw/sound_timer_pcm.raw").toPath());
        assertEquals(0, shutter.length % 2);
        assertEquals(0, timer.length % 2);
        float shutterS = shutter.length / 2f / CameraSounds.PCM_RATE, timerS = timer.length / 2f / CameraSounds.PCM_RATE;
        assertTrue("shutter " + shutterS + " s", shutterS > 0.1f && shutterS < 0.2f);
        assertTrue("timer " + timerS + " s", timerS > 10.5f && timerS < 11f);
        short[] s = CameraSounds.samples(shutter);
        int peak = 0;
        for (short v : s) peak = Math.max(peak, Math.abs(v));
        assertTrue("an audible shutter: peak " + peak, peak > 10000);
        assertEquals("little endian", (short) 0x8001, CameraSounds.samples(new byte[]{1, (byte) 0x80})[0]);
        // no synthesized beeps any more
        String code = new String(Files.readAllBytes(new File("src/main/java/com/particlesdevs/photoncamera/ui/camera/CameraSounds.java").toPath()), StandardCharsets.UTF_8);
        assertFalse(code.contains("ToneGenerator"));
        assertTrue(code.contains("R.raw.sound_shutter_pcm") && code.contains("R.raw.sound_timer_pcm"));
    }
}
