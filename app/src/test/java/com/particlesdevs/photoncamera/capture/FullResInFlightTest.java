package com.particlesdevs.photoncamera.capture;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * P222 (roadmap speed-ux A3; the owner's X7 Ultra log 2026-10-17: FULLRES 50 MP stills in groups of 3 with 4-5 preview frames
 * between them): the stills a FULLRES series keeps in flight, and the memory that takes a camera's size back to P179's 3 after a
 * failed series with more.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class FullResInFlightTest {
    private static final VivoSdkFullRes.Profile QUAD_50 = new VivoSdkFullRes.Profile(8192, 6144, 4, 2);
    private static final float UNSET = 0f;

    private static SharedPreferences prefs() {
        SharedPreferences p = RuntimeEnvironment.getApplication().getSharedPreferences("p222", Context.MODE_PRIVATE);
        p.edit().clear().commit();
        return p;
    }

    @Test public void theNativeRouteKeepsSixInFlightUpTo100MpAndP179sThreeAbove() {
        assertEquals(6, FullResInFlight.choose(12, true, false, UNSET, null).count);
        assertEquals("never more than the series", 4, FullResInFlight.choose(4, true, false, UNSET, null).count);
        assertEquals(1, FullResInFlight.choose(1, true, false, UNSET, null).count);
        assertEquals("default", FullResInFlight.choose(12, true, false, UNSET, null).source);
        assertEquals("200 MP: P179's 3", 3, FullResInFlight.choose(4, true, true, UNSET, null).count);
        assertEquals(2, FullResInFlight.choose(2, true, true, UNSET, null).count);
        assertTrue(FullResInFlight.huge(VivoSdkFullRes.TELE_200));
        assertFalse(FullResInFlight.huge(VivoSdkFullRes.TELE_50));
        assertFalse(FullResInFlight.huge(QUAD_50));
        assertFalse("the X300 Ultra main's 50 MP row", FullResInFlight.huge(new VivoSdkFullRes.Profile(8160, 6128, 3, 2)));
    }

    @Test public void theSdkRouteStaysOneAtATimeWhateverIsAsked() {
        assertEquals(1, FullResInFlight.choose(12, false, false, UNSET, null).count);
        assertEquals(1, FullResInFlight.choose(12, false, false, 8f, null).count);
        assertEquals(1, FullResInFlight.choose(12, false, false, UNSET, "SDK STILL buffer lost").count);
    }

    @Test public void scamDevAsksForAnyCountFrom1To8OnEveryNativeRoute() {
        assertEquals("3 = before P222", 3, FullResInFlight.choose(12, true, false, 3f, null).count);
        assertEquals(8, FullResInFlight.choose(12, true, false, 8f, null).count);
        assertEquals(1, FullResInFlight.choose(12, true, false, 1f, null).count);
        assertEquals("above 100 MP as well", 4, FullResInFlight.choose(4, true, true, 6f, null).count);
        assertEquals("never more than the series", 5, FullResInFlight.choose(5, true, false, 8f, null).count);
        assertEquals("the owner's test value wins over the memory", 8, FullResInFlight.choose(12, true, false, 8f, "timeout").count);
        assertTrue(FullResInFlight.choose(12, true, false, 8f, "timeout").source.contains("timeout"));
        assertTrue(FullResInFlight.choose(12, true, false, 8f, null).source.contains("scam_dev"));
        for (float bad : new float[]{9f, -1f, 2.5f, Float.NaN, 100f}) {
            FullResInFlight.Choice c = FullResInFlight.choose(12, true, false, bad, null);
            assertEquals("ignored: " + bad, 6, c.count);
        }
        assertTrue(FullResInFlight.choose(12, true, false, 9f, null).source.contains("ignored"));
    }

    @Test public void aNotedFailureTakesTheDefaultBackToThree() {
        FullResInFlight.Choice c = FullResInFlight.choose(12, true, false, UNSET, "SDK STILL failed: 1");
        assertEquals(3, c.count);
        assertTrue(c.source, c.source.contains("SDK STILL failed: 1"));
        assertEquals(2, FullResInFlight.choose(2, true, false, UNSET, "timeout").count);
    }

    /** What may be the count's fault: everything but a wrong mode / mosaic (the row memory's) and the camera closed under it. */
    @Test public void onlyFailuresTheCountMayCauseAreNoted() {
        for (String why : new String[]{"SDK STILL failed: 1", "SDK STILL buffer lost", "SDK STILL aborted",
                "SDK RAW/result/JPEG timeout at frame 4", "SDK FULLRES50 delivered an empty or invalid RAW buffer", "RAW copy failed: x",
                "Incomplete SDK RAW series", "Invalid SDK RAW metadata", "RAW result does not match its capture start",
                "Invalid or duplicate RAW capture timestamp", "SDK RAW frame missing", "camera error 4",
                "1 of 6 stills after the shutter missing", "SDK STILL submit failed: closed", null})
            assertTrue(String.valueOf(why), FullResInFlight.counts(why));
        for (String why : new String[]{"SDK FULLRES50 sensor mode is not active", "Native FULLRES mosaic does not match its sensor profile",
                "Native FULLRES mosaic has not been verified from RAW pixels", "camera closed", "camera disconnected"})
            assertFalse(why, FullResInFlight.counts(why));
    }

    @Test public void aFailedSeriesWithMoreThanThreeIsRememberedPerCameraAndSize() {
        SharedPreferences p = prefs();
        assertNull(FullResInFlight.fallback(p, "2", QUAD_50));
        assertFalse("3 in flight: the count before P222, nothing to learn", FullResInFlight.failed(p, "2", QUAD_50, 3, "SDK STILL buffer lost"));
        assertNull(FullResInFlight.fallback(p, "2", QUAD_50));
        assertFalse("a wrong mosaic is the row's", FullResInFlight.failed(p, "2", QUAD_50, 6, "Native FULLRES mosaic does not match its sensor profile"));
        assertFalse("the camera closed under it", FullResInFlight.failed(p, "2", QUAD_50, 6, "camera closed"));
        assertNull(FullResInFlight.fallback(p, "2", QUAD_50));
        assertTrue(FullResInFlight.failed(p, "2", QUAD_50, 6, "SDK RAW/result/JPEG timeout at frame 5"));
        assertEquals("SDK RAW/result/JPEG timeout at frame 5", FullResInFlight.fallback(p, "2", QUAD_50));
        assertEquals("the same size of another mode", "SDK RAW/result/JPEG timeout at frame 5",
                FullResInFlight.fallback(p, "2", new VivoSdkFullRes.Profile(8192, 6144, 29, 2)));
        assertNull("another camera", FullResInFlight.fallback(p, "3", QUAD_50));
        assertNull("another size of the camera", FullResInFlight.fallback(p, "2", VivoSdkFullRes.TELE_200));
        assertEquals(3, FullResInFlight.choose(12, true, false, UNSET, FullResInFlight.fallback(p, "2", QUAD_50)).count);
        assertEquals(6, FullResInFlight.choose(12, true, false, UNSET, FullResInFlight.fallback(p, "3", QUAD_50)).count);
    }

    @Test public void aFailureOfAnotherGenerationIsForgottenAndLongReasonsAreCut() {
        SharedPreferences p = prefs();
        final String key = FullResInFlight.key("2", QUAD_50);
        p.edit().putInt(key, FullResInFlight.TRIALS + 1).putString(key + "_why", "old").commit();
        assertNull(FullResInFlight.fallback(p, "2", QUAD_50));
        StringBuilder longWhy = new StringBuilder("SDK STILL failed: ");
        while (longWhy.length() < 400) longWhy.append('x');
        assertTrue(FullResInFlight.failed(p, "2", QUAD_50, 8, longWhy.toString()));
        assertEquals(160, FullResInFlight.fallback(p, "2", QUAD_50).length());
        assertTrue(FullResInFlight.failed(p, "4", QUAD_50, 6, null));
        assertEquals("unknown", FullResInFlight.fallback(p, "4", QUAD_50));
        assertNull(FullResInFlight.fallback(null, "2", QUAD_50));
        assertNull(FullResInFlight.fallback(p, null, QUAD_50));
        assertFalse(FullResInFlight.failed(null, "2", QUAD_50, 6, "timeout"));
    }
}
