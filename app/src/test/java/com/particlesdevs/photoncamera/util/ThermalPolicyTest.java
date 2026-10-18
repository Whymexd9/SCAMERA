package com.particlesdevs.photoncamera.util;

import android.app.Application;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** P238 (speed-ux D2): the hot-phone policy - the viewfinder cap, the press notice, the status names. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ThermalPolicyTest {
    @After public void cleanup() {
        ThermalPolicy.set(-1);
        ThermalPolicy.resetNotice();
    }

    @Test public void belowSevereEveryViewfinderFrameIsShown() {
        ThermalPolicy.resetNotice();
        ThermalPolicy.set(2); // MODERATE
        assertFalse(ThermalPolicy.severe());
        for (int i = 0; i < 30; i++) assertTrue(ThermalPolicy.viewfinderFrameDue(1_000_000_000L + i * 33_333_333L));
        assertNull("no notice below SEVERE", ThermalPolicy.pressNotice(10_000, 50));
    }

    @Test public void atSevereTheViewfinderShowsAtMost15FramesASecond() {
        ThermalPolicy.resetNotice();
        ThermalPolicy.set(3);
        assertTrue(ThermalPolicy.severe());
        int shown = 0;
        for (int i = 0; i < 60; i++) if (ThermalPolicy.viewfinderFrameDue(5_000_000_000L + i * 33_333_333L)) shown++; // 2 s at 30 fps
        assertEquals("every second frame of a 30 fps stream", 30, shown);
        shown = 0;
        for (int i = 0; i < 48; i++) if (ThermalPolicy.viewfinderFrameDue(9_000_000_000L + i * 41_666_666L)) shown++; // 2 s at 24 fps
        assertTrue("a 24 fps stream at most 15 a second: " + shown, shown <= 30 && shown >= 20);
    }

    @Test public void theHotNoticeComesOnceInFiveMinutes() {
        ThermalPolicy.resetNotice();
        ThermalPolicy.set(4); // CRITICAL
        final String first = ThermalPolicy.pressNotice(1_000_000, 50);
        assertNotNull(first);
        assertTrue(first, first.contains("50"));
        assertNull("not again at once", ThermalPolicy.pressNotice(1_000_000 + 60_000, 50));
        assertNotNull("after five minutes", ThermalPolicy.pressNotice(1_000_000 + ThermalPolicy.NOTICE_EVERY_MS, 200));
    }

    @Test public void statusNamesAndSamples() {
        assertEquals("severe", ThermalState.name(3));
        assertEquals("unknown", ThermalState.name(-1));
        assertEquals("moderate/0.85", new ThermalState.Sample(2, 0.85f).toString());
        assertEquals("unknown", ThermalState.UNKNOWN.toString());
        assertTrue(new ThermalState.Sample(3, Float.NaN).severe());
        assertFalse(new ThermalState.Sample(2, Float.NaN).severe());
        ThermalPolicy.observed(new ThermalState.Sample(3, 1.0f));
        assertEquals(3, ThermalPolicy.status());
        ThermalPolicy.observed(ThermalState.UNKNOWN);
        assertEquals("an unknown sample keeps the last status", 3, ThermalPolicy.status());
    }
}
