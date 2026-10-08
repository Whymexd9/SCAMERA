package com.particlesdevs.photoncamera.ui.camera.views;

import android.app.Application;

import com.particlesdevs.photoncamera.circularbarlib.ui.ManualFormat;
import com.particlesdevs.photoncamera.circularbarlib.ui.ManualPanelState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/** Chip texts of the manual controls (MANUAL_TASK.md §5) and the EV lock rule of the panel. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ManualFormatTest {
    private static ManualFormat.Units units() {
        return ManualFormat.Units.of(RuntimeEnvironment.getApplication());
    }

    @Test
    @Config(qualifiers = "ru")
    public void russianChipTexts() {
        ManualFormat.Units ru = units();
        assertEquals("2560", ManualFormat.iso(2560));
        assertEquals("1/8000", ManualFormat.shutter(125_000L, ru));
        assertEquals("1/125", ManualFormat.shutter(8_000_000L, ru));
        assertEquals("1/3", ManualFormat.shutter(333_333_333L, ru));
        assertEquals("2 с", ManualFormat.shutter(2_000_000_000L, ru));
        assertEquals("1,3 с", ManualFormat.shutter(1_300_000_000L, ru));
        assertEquals("30 с", ManualFormat.shutter(30_000_000_000L, ru));
        assertEquals("∞", ManualFormat.focus(0, ru));
        assertEquals("1,2 м", ManualFormat.focus(1 / 1.2, ru));
        assertEquals("2 м", ManualFormat.focus(0.5, ru));
        assertEquals("30 см", ManualFormat.focus(1 / 0.3, ru));
        assertEquals("15 м", ManualFormat.focus(1 / 15.0, ru));
        assertEquals("5200K", ManualFormat.wb(5200));
    }

    @Test
    public void englishChipTexts() {
        ManualFormat.Units en = units();
        assertEquals("2 s", ManualFormat.shutter(2_000_000_000L, en));
        assertEquals("1.3 s", ManualFormat.shutter(1_300_000_000L, en));
        assertEquals("1.2 m", ManualFormat.focus(1 / 1.2, en));
        assertEquals("30 cm", ManualFormat.focus(1 / 0.3, en));
        assertEquals("1/8000", ManualFormat.shutter(125_000L, en));
        assertEquals(ManualFormat.EN.seconds, en.seconds);
    }

    @Test
    public void evInThirds() {
        assertEquals("0", ManualFormat.ev(0));
        assertEquals("+1 1/3", ManualFormat.ev(4 / 3.0));
        assertEquals("−2/3", ManualFormat.ev(-2 / 3.0));
        assertEquals("+1/3", ManualFormat.ev(1 / 3f)); // a float step, as Camera2 gives it
        assertEquals("−1", ManualFormat.ev(-3 * (1 / 3f)));
        assertEquals("+2", ManualFormat.ev(2));
        assertEquals("−1 2/3", ManualFormat.ev(-5 / 3.0));
        assertEquals("+1/2", ManualFormat.ev(0.5));
        assertEquals("+1/6", ManualFormat.ev(1 / 6f));
        assertTrue(ManualFormat.ev(-1).startsWith("−"));
    }

    @Test
    public void evLockRule() {
        // ISO and shutter manual: EV is locked, its stored value kept but not applied, not in the summary, no dot
        ManualPanelState locked = new ManualPanelState(true, true, true, false, false);
        assertTrue(locked.evLocked());
        assertTrue(locked.isManual(ManualPanelState.EV));
        assertFalse(locked.isApplied(ManualPanelState.EV));
        assertEquals(Arrays.asList(ManualPanelState.ISO, ManualPanelState.SHUTTER), locked.summary());
        assertEquals(0, ManualPanelState.effectiveEvIndex(4, true, true));
        // one of them back to auto: EV applies again
        ManualPanelState iso = new ManualPanelState(true, false, true, false, false);
        assertFalse(iso.evLocked());
        assertTrue(iso.isApplied(ManualPanelState.EV));
        assertEquals(Arrays.asList(ManualPanelState.ISO, ManualPanelState.EV), iso.summary());
        assertEquals(4, ManualPanelState.effectiveEvIndex(4, true, false));
        assertEquals(-2, ManualPanelState.effectiveEvIndex(-2, false, true));
        // only a locked EV would not light anything; all auto: nothing
        assertTrue(locked.anyApplied());
        assertFalse(new ManualPanelState(false, false, false, false, false).anyManual());
        assertEquals(Collections.emptyList(), new ManualPanelState(false, false, false, false, false).summary());
        assertEquals(Arrays.asList(0, 1, 3, 4), new ManualPanelState(true, true, false, true, true).summary());
    }
}
