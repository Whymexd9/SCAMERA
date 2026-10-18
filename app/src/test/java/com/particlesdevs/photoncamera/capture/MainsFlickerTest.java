package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.hardware.camera2.CaptureResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;

/**
 * P118 «Частота мерцания» Авто: the camera's scene flicker, else the AE's anti-banding shutters, else the region. P212d: a report
 * counts after the session's first results, outside AE-off results and native sessions, on OPPO only with IsFlickerDetected 1,
 * REPORTS in a row latch; the merge's row profile votes (two shots) override.
 */
public class MainsFlickerTest {
    private static final int F50 = CaptureResult.STATISTICS_SCENE_FLICKER_50HZ, F60 = CaptureResult.STATISTICS_SCENE_FLICKER_60HZ;
    private Locale locale;

    @Before public void setUp() { locale = Locale.getDefault(); MainsFlicker.resetForTest(); }
    @After public void tearDown() { Locale.setDefault(locale); MainsFlicker.resetForTest(); }

    /** P212d: the camera's report latches after the session's first results and REPORTS agreeing ones in a row, for good. */
    @Test public void theCameraSceneFlickerLatchesOnAgreeingReportsForGood() {
        Locale.setDefault(Locale.US);
        MainsFlicker.newSession(false);
        for (int i = 0; i < MainsFlicker.SKIP_RESULTS + MainsFlicker.REPORTS - 1; i++) MainsFlicker.observe(F50, 4_000_000L, true);
        assertFalse("one report short", MainsFlicker.known());
        assertEquals("the region's 60 Hz meanwhile", 120, MainsFlicker.hz());
        MainsFlicker.observe(F50, 4_000_000L, true);
        assertTrue(MainsFlicker.known());
        assertEquals(100, MainsFlicker.hz());
        for (int i = 0; i < 20; i++) MainsFlicker.observe(F60, 4_000_000L, true);
        assertEquals("latched for good", 100, MainsFlicker.hz());
    }

    /** P212d: the PHY110's placeholder - 60 Hz on frame 0, 50 Hz after, IsFlickerDetected 0 throughout - latches nothing on OPPO. */
    @Test public void thePlaceholderReportOfAnOppoNeverLatches() {
        Locale.setDefault(new Locale("ru", "RU"));
        MainsFlicker.newSession(false);
        MainsFlicker.observe(F60, 30_000_000L, true, 0, true, false);
        for (int i = 0; i < 100; i++) MainsFlicker.observe(F50, 30_000_000L, true, 0, true, false);
        assertFalse(MainsFlicker.known());
        // a HAL without the key: no verdict, no latch either
        for (int i = 0; i < 100; i++) MainsFlicker.observe(F50, 30_000_000L, true, null, true, false);
        assertFalse(MainsFlicker.known());
        assertEquals("the region's guess", 100, MainsFlicker.hz());
    }

    /** P212d: before, frame 0's 60 Hz latched 120 Hz at once; now frame 0 is never read, the reports after it decide. */
    @Test public void frameZeroOfASessionIsNeverRead() {
        Locale.setDefault(new Locale("ru", "RU"));
        MainsFlicker.newSession(false);
        MainsFlicker.observe(F60, 30_000_000L, true);
        assertFalse("today's latch from frame 0 is gone", MainsFlicker.known());
        for (int i = 0; i < 20; i++) MainsFlicker.observe(F50, 30_000_000L, true);
        assertEquals(100, MainsFlicker.hz());
        assertTrue(MainsFlicker.source().startsWith("camera"));
    }

    /** P212d: an OPPO phone's reports with IsFlickerDetected 1 on the same result latch after REPORTS of them. */
    @Test public void detectedReportsOfAnOppoLatch() {
        Locale.setDefault(Locale.US);
        MainsFlicker.newSession(false);
        for (int i = 0; i < MainsFlicker.SKIP_RESULTS; i++) MainsFlicker.observe(F50, 30_000_000L, true, 1, true, false);
        for (int i = 0; i < MainsFlicker.REPORTS - 1; i++) MainsFlicker.observe(F50, 30_000_000L, true, 1, true, false);
        assertFalse(MainsFlicker.known());
        // a verdict 0 in between breaks the run
        MainsFlicker.observe(F50, 30_000_000L, true, 0, true, false);
        for (int i = 0; i < MainsFlicker.REPORTS - 1; i++) MainsFlicker.observe(F50, 30_000_000L, true, 1, true, false);
        assertFalse(MainsFlicker.known());
        MainsFlicker.observe(F50, 30_000_000L, true, 1, true, false);
        assertEquals(100, MainsFlicker.hz());
    }

    /** P212d: native sessions, an OPPO native session, AE-off results are not read; a NONE report breaks a run. */
    @Test public void nativeAndAeOffResultsAreIgnored() {
        Locale.setDefault(Locale.US);
        MainsFlicker.newSession(true); // a vivo native route with its own RAW viewfinder
        for (int i = 0; i < 50; i++) MainsFlicker.observe(F50, 30_000_000L, true);
        MainsFlicker.newSession(false);
        for (int i = 0; i < 50; i++) MainsFlicker.observe(F50, 30_000_000L, true, 1, true, true); // OppoNativeFullRes.running
        for (int i = 0; i < 50; i++) MainsFlicker.observe(F50, 30_000_000L, false);              // AE off
        assertFalse(MainsFlicker.known());
        for (int i = 0; i < MainsFlicker.REPORTS - 1; i++) MainsFlicker.observe(F50, 30_000_000L, true);
        MainsFlicker.observe(CaptureResult.STATISTICS_SCENE_FLICKER_NONE, 30_000_000L, true);
        for (int i = 0; i < MainsFlicker.REPORTS - 1; i++) MainsFlicker.observe(F50, 30_000_000L, true);
        assertFalse("NONE broke the run", MainsFlicker.known());
        MainsFlicker.observe(F50, 30_000_000L, true);
        assertEquals(100, MainsFlicker.hz());
    }

    /** P212d: two agreeing shot votes of the row profile latch over a report latch; a later pair of other votes wins again. */
    @Test public void anEvidenceLatchOverridesAReportLatch() {
        Locale.setDefault(new Locale("ru", "RU"));
        MainsFlicker.newSession(false);
        for (int i = 0; i < 20; i++) MainsFlicker.observe(F60, 30_000_000L, true);
        assertEquals(120, MainsFlicker.hz());
        assertFalse("one vote is no latch", MainsFlicker.shotVote(100));
        assertFalse("no vote changes nothing, breaks nothing", MainsFlicker.shotVote(0));
        assertEquals(120, MainsFlicker.hz());
        assertTrue(MainsFlicker.shotVote(100));
        assertEquals(100, MainsFlicker.hz());
        assertTrue(MainsFlicker.source().startsWith("row profile"));
        for (int i = 0; i < 20; i++) MainsFlicker.observe(F60, 30_000_000L, true);
        assertEquals("reports never undo evidence", 100, MainsFlicker.hz());
        assertFalse(MainsFlicker.shotVote(100));
        assertFalse(MainsFlicker.shotVote(120));
        assertEquals(100, MainsFlicker.hz());
        assertTrue(MainsFlicker.shotVote(120));
        assertEquals(120, MainsFlicker.hz());
    }

    /** P212d: the AFD tag's first value, whatever integer type the HAL declares it with. */
    @Test public void theAfdVerdictIsReadFromIntOrIntArray() {
        assertEquals(Integer.valueOf(1), MainsFlicker.afdValue(new int[]{1, 0}));
        assertEquals(Integer.valueOf(0), MainsFlicker.afdValue(0));
        assertEquals(Integer.valueOf(1), MainsFlicker.afdValue(new byte[]{1}));
        assertEquals(Integer.valueOf(1), MainsFlicker.afdValue((byte) 1));
        assertNull(MainsFlicker.afdValue(new int[0]));
        assertNull(MainsFlicker.afdValue(null));
        assertNull(MainsFlicker.afdValue("1"));
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
        assertFalse(MainsFlicker.known());
    }

    @Test public void regionMains() {
        assertEquals(100, MainsFlicker.regionHz("RU"));
        assertEquals(100, MainsFlicker.regionHz("DE"));
        assertEquals(120, MainsFlicker.regionHz("us"));
        assertEquals(120, MainsFlicker.regionHz("KR"));
        assertEquals(100, MainsFlicker.regionHz(""));
    }
}
