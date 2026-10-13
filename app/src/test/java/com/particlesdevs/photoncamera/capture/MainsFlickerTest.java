package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;

import android.hardware.camera2.CaptureResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;

/** P118 «Частота мерцания» Авто: the camera's scene flicker, else the AE's anti-banding shutters, else the region. */
public class MainsFlickerTest {
    private Locale locale;

    @Before public void setUp() { locale = Locale.getDefault(); MainsFlicker.resetForTest(); }
    @After public void tearDown() { Locale.setDefault(locale); MainsFlicker.resetForTest(); }

    @Test public void theCameraSceneFlickerLatchesAtOnce() {
        Locale.setDefault(Locale.US);
        MainsFlicker.observe(CaptureResult.STATISTICS_SCENE_FLICKER_50HZ, 4_000_000L, true);
        assertEquals(100, MainsFlicker.hz());
        MainsFlicker.observe(CaptureResult.STATISTICS_SCENE_FLICKER_60HZ, 4_000_000L, true);
        assertEquals("latched for good", 100, MainsFlicker.hz());
    }

    @Test public void antiBandingShuttersAtTwoMultiplesDecide() {
        Locale.setDefault(new Locale("ru", "RU"));
        // 60 Hz lamps: 16.67 / 25 ms shutters of the AE; one value held proves nothing
        for (int i = 0; i < 10; i++) MainsFlicker.observe(CaptureResult.STATISTICS_SCENE_FLICKER_NONE, 16_666_666L, true);
        assertEquals("region until two multiples", 100, MainsFlicker.hz());
        for (int i = 0; i < 2; i++) MainsFlicker.observe(null, 25_000_000L, true);
        assertEquals(120, MainsFlicker.hz());
    }

    @Test public void shortManualOrCommonShuttersDoNotVote() {
        Locale.setDefault(Locale.US);
        for (int i = 0; i < 20; i++) {
            MainsFlicker.observe(null, 2_000_000L, true);   // shorter than a period
            MainsFlicker.observe(null, 50_000_000L, true);  // a multiple of both
            MainsFlicker.observe(null, 20_000_000L, false); // AE off: the user's shutter
            MainsFlicker.observe(null, 30_000_000L, false);
        }
        assertEquals("the region's 60 Hz", 120, MainsFlicker.hz());
    }

    @Test public void regionMains() {
        assertEquals(100, MainsFlicker.regionHz("RU"));
        assertEquals(100, MainsFlicker.regionHz("DE"));
        assertEquals(120, MainsFlicker.regionHz("us"));
        assertEquals(120, MainsFlicker.regionHz("KR"));
        assertEquals(100, MainsFlicker.regionHz(""));
    }
}
