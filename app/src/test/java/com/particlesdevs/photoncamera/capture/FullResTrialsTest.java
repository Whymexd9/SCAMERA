package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Map;

/**
 * P214h (speed-ux C2, engineering B3): a camera's FULLRES row trials are stamped with the route revision and the firmware (a
 * firmware update tries the rows again, a build before P214h keeps its trials), «Попробовать снова» forgets exactly that
 * camera's keys, and the status says which rows failed and why; a 50 MP merge is named as 50 MP.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class FullResTrialsTest {
    private static final VivoNativeFullRes.Phone X200 = VivoNativeFullRes.Phone.X200_ULTRA,
            X100 = VivoNativeFullRes.Phone.X100_ULTRA, X300 = VivoNativeFullRes.Phone.X300_ULTRA;
    private static final String A = FullResTrials.stamp(1, "vivo/PD2366/PD2366:15/AP3A.240905.015/A:user/release-keys"),
            B = FullResTrials.stamp(1, "vivo/PD2366/PD2366:16/BP2A.250605.031/B:user/release-keys"),
            R2 = FullResTrials.stamp(2, "vivo/PD2366/PD2366:15/AP3A.240905.015/A:user/release-keys");
    private static final String NO_FRAME = "no preview frame for 3000 ms";
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> app;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        app = mockStatic(ScameraApp.class);
        // the settings keys resolve their names through it: never null under the mock (a Key enum first loaded here kept
        // null names for every later test of the JVM)
        app.when(() -> ScameraApp.getStringStatic(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> org.robolectric.RuntimeEnvironment.getApplication().getString(i.<Integer>getArgument(0)));
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
    }

    @After
    public void tearDown() {
        PixelPro10.suspendFullRes(false);
        prefs.edit().clear().commit();
        if (app != null) app.close();
    }

    @Test
    public void theStampIsTheRouteRevisionAndTheFirmware() {
        assertEquals("r1 fp", FullResTrials.stamp(1, "fp"));
        assertEquals("r1 ", FullResTrials.stamp(1, null));
        assertFalse(A.equals(B));
        assertFalse(A.equals(R2));
        assertEquals(FullResTrials.stamp(VivoNativeFullRes.ROW_TRIALS, android.os.Build.FINGERPRINT), VivoNativeFullRes.stamp());
        assertEquals(FullResTrials.stamp(OppoNativeFullRes.ROW_TRIALS, android.os.Build.FINGERPRINT), OppoNativeFullRes.stamp());
    }

    /** B3: trials of another firmware or route revision are passed over, and the next failure replaces them. */
    @Test
    public void aFirmwareUpdateOrANewRouteRevisionTriesTheRowsAgain() {
        VivoNativeFullRes.noteRow(prefs, X100, "2", false, 1, 11, NO_FRAME, A);
        assertEquals(1, VivoNativeFullRes.failures(prefs, X100, "2", false, A));
        assertEquals("another firmware", 0, VivoNativeFullRes.failures(prefs, X100, "2", false, B));
        assertEquals("another route revision", 0, VivoNativeFullRes.failures(prefs, X100, "2", false, R2));
        VivoNativeFullRes.noteHardware(prefs, "2", "the frames are remosaicked by the HAL (plain Bayer, no mosaic)", A);
        assertTrue(VivoNativeFullRes.hardwareFailed(prefs, "2", A));
        assertFalse(VivoNativeFullRes.hardwareFailed(prefs, "2", B));
        // a failure on the new firmware: the old trials go, only the new one counts
        VivoNativeFullRes.noteRow(prefs, X100, "2", false, 1, 11, "camera error 4", B);
        assertEquals(1, VivoNativeFullRes.failures(prefs, X100, "2", false, B));
        assertFalse("the old firmware's hardware row is gone", VivoNativeFullRes.hardwareFailed(prefs, "2", B));
        assertEquals("11:died", VivoNativeFullRes.reasons(prefs, X100, "2", false, B));
        assertEquals(0, VivoNativeFullRes.failures(prefs, X100, "2", false, A));
        // the OPPO's trials likewise
        OppoNativeFullRes.note(prefs, "2", 1, 35, "Native FULLRES mosaic does not match its sensor profile", A);
        assertEquals(1, OppoNativeFullRes.failures(prefs, "2", A));
        assertEquals(0, OppoNativeFullRes.failures(prefs, "2", B));
        OppoNativeFullRes.note(prefs, "2", 1, 35, NO_FRAME, B);
        assertEquals(1, OppoNativeFullRes.failures(prefs, "2", B));
        assertEquals("35:noframe", OppoNativeFullRes.reasons(prefs, "2", B));
    }

    /** The trials of a build before P214h (no stamp, the keys P210 wrote) count and get this firmware's stamp. */
    @Test
    public void theTrialsOfABuildBeforeTheStampAreKept() {
        prefs.edit().putInt("module_fullres_rowfail_trials", 1).putInt("module_fullres_rowfail_2_50_r11", 2)
                .putBoolean("module_fullres_rowfail_3_hw", true).putInt("module_oppo_fullres_fail_2", 1).commit();
        assertEquals(2, VivoNativeFullRes.failures(prefs, X100, "2", false, A));
        assertEquals(A, prefs.getString("module_fullres_rowfail_2_stamp", null));
        assertEquals("stamped now: an update of the firmware tries them again", 0, VivoNativeFullRes.failures(prefs, X100, "2", false, B));
        assertTrue(VivoNativeFullRes.hardwareFailed(prefs, "3", A));
        assertEquals(1, OppoNativeFullRes.failures(prefs, "2", A));
        assertEquals(A, prefs.getString("module_oppo_fullres_fail_2_stamp", null));
        // a camera without trials gets no stamp
        assertEquals(0, VivoNativeFullRes.failures(prefs, X100, "4", true, A));
        assertFalse(prefs.contains("module_fullres_rowfail_4_stamp"));
        // the old route revision's trials (ROW_TRIALS 0) never counted and still do not
        prefs.edit().clear().putInt("module_fullres_rowfail_trials", 0).putInt("module_fullres_rowfail_2_50_r11", 2).commit();
        assertEquals(0, VivoNativeFullRes.failures(prefs, X100, "2", false, A));
    }

    /** «Попробовать снова»: the camera's keys go (every size, the hardware row, the reasons, the stamp, renamed ones), no other. */
    @Test
    public void tryAgainForgetsThatCameraOnly() {
        prefs.edit().putInt("module_fullres_rowfail_trials", 1)
                .putInt("module_fullres_rowfail_2_50_r11", 2).putString("module_fullres_rowfail_2_50_r11_why", "11:noframe,31:noframe")
                .putInt("module_fullres_rowfail_2_50", 3) // P207 renamed it to _r11: forgotten too
                .putBoolean("module_fullres_rowfail_2_hw", true).putString("module_fullres_rowfail_2_hw_why", "mosaic")
                .putString("module_fullres_rowfail_2_stamp", A)
                .putInt("module_fullres_rowfail_20_50", 1) // camera 20
                .putBoolean("module_fullres_camera_2", true) // FullResProfiles' offer, not a trial
                .putInt("module_oppo_fullres_fail_2", 1).putString("module_oppo_fullres_fail_2_why", "35:mosaic")
                .putString("module_oppo_fullres_fail_2_stamp", A).putInt("module_oppo_fullres_fail_20", 1).commit();
        assertEquals(6, VivoNativeFullRes.clearFailures(prefs, "2"));
        assertEquals(3, OppoNativeFullRes.clearFailures(prefs, "2"));
        Map<String, ?> left = prefs.getAll();
        assertEquals(4, left.size());
        assertTrue(left.containsKey("module_fullres_rowfail_trials"));
        assertTrue(left.containsKey("module_fullres_rowfail_20_50"));
        assertTrue(left.containsKey("module_fullres_camera_2"));
        assertTrue(left.containsKey("module_oppo_fullres_fail_20"));
        assertEquals(0, VivoNativeFullRes.failures(prefs, X100, "2", false, A));
        assertFalse(VivoNativeFullRes.hardwareFailed(prefs, "2", A));
        assertEquals(0, OppoNativeFullRes.failures(prefs, "2", A));
        assertNull(VivoNativeFullRes.status(prefs, X100, "2", A, false));
        assertEquals("nothing left to forget", 0, VivoNativeFullRes.clearFailures(prefs, "2"));
        assertEquals(0, VivoNativeFullRes.clearFailures(prefs, null));
        assertEquals(0, OppoNativeFullRes.clearFailures(null, "2"));
    }

    /** The facade forgets both routes' keys of the camera (the app's own preferences). */
    @Test
    public void theFacadeRetriesTheCameraOfBothRoutes() {
        VivoNativeFullRes.noteRow(prefs, X100, "3", false, 1, 9, NO_FRAME, A);
        OppoNativeFullRes.note(prefs, "3", 1, 16, NO_FRAME, A);
        VivoNativeFullRes.noteRow(prefs, X100, "4", true, 1, 0, NO_FRAME, A);
        FullResTrials.retry("3");
        assertEquals(0, VivoNativeFullRes.failures(prefs, X100, "3", false, A));
        assertEquals(0, OppoNativeFullRes.failures(prefs, "3", A));
        assertEquals("another camera keeps its trials", 1, VivoNativeFullRes.failures(prefs, X100, "4", true, A));
        assertTrue(FullResTrials.dump("4").contains("module_fullres_rowfail_4_200=1"));
        assertEquals("", FullResTrials.dump("3"));
    }

    /** The reasons CaptureController / VivoSdkFullResCapture give, as kept for the status. */
    @Test
    public void theFailureReasonsAreKeptShort() {
        assertEquals(FullResTrials.NO_FRAME, FullResTrials.code(NO_FRAME));
        assertEquals(FullResTrials.DIED, FullResTrials.code("camera error 4"));
        assertEquals(FullResTrials.REFUSED, FullResTrials.code("session configuration failed"));
        assertEquals(FullResTrials.REFUSED, FullResTrials.code("session: CAMERA_ERROR (3): createCaptureSession"));
        assertEquals(FullResTrials.REFUSED, FullResTrials.code("preview: Request targets Surface that is not part of this session"));
        assertEquals(FullResTrials.HW_REMOSAIC, FullResTrials.code("the frames are remosaicked by the HAL (plain Bayer, no mosaic)"));
        assertEquals(FullResTrials.MOSAIC, FullResTrials.code("the hardware-remosaic row gives a block 2 mosaic, not a Bayer"));
        assertEquals(FullResTrials.MOSAIC, FullResTrials.code("Native FULLRES mosaic does not match its sensor profile"));
        assertEquals(FullResTrials.MOSAIC, FullResTrials.code("Native FULLRES mosaic has not been verified from RAW pixels"));
        assertEquals(FullResTrials.MODE, FullResTrials.code("SDK FULLRES50 sensor mode is not active"));
        assertEquals(FullResTrials.OTHER, FullResTrials.code(null));
        assertEquals("11:noframe,31:died", FullResTrials.withReason("11:noframe", 31, FullResTrials.DIED));
        assertEquals("a row failing again moves to the end", "31:died,11:mosaic",
                FullResTrials.withReason("11:noframe,31:died", 11, FullResTrials.MOSAIC));
        assertEquals("malformed entries are passed over", "{9=noframe}", FullResTrials.reasons("x:died,9:noframe,:mode,7").toString());
    }

    /** speed-ux C2: «rows 11/31 gave no frame; row 0 is the camera's own remosaic -> «FULLRES: аппаратный ремозаик»». */
    @Test
    public void theStatusSaysWhichRowsFailedAndWhy() {
        assertNull("nothing failed", VivoNativeFullRes.status(prefs, X100, "2", A, false));
        VivoNativeFullRes.noteRow(prefs, X100, "2", false, 1, 11, NO_FRAME, A);
        assertEquals("FULLRES 50 MP: mode 11 — no frame; next mode 31.\n"
                        + "Mode 0 is the camera's own hardware remosaic: with “FULLRES: hardware remosaic” (Shooting) 50 MP shots are taken by it.",
                VivoNativeFullRes.status(prefs, X100, "2", A, false));
        VivoNativeFullRes.noteRow(prefs, X100, "2", false, 2, 31, NO_FRAME, A);
        assertEquals("FULLRES 50 MP is off: modes 11, 31 — no frame.\n"
                        + "Mode 0 is the camera's own hardware remosaic: with “FULLRES: hardware remosaic” (Shooting) 50 MP shots are taken by it.",
                VivoNativeFullRes.status(prefs, X100, "2", A, false));
        assertEquals("FULLRES 50 MP is off: modes 11, 31 — no frame.\n50 MP shots are taken by the camera's hardware remosaic now (mode 0).",
                VivoNativeFullRes.status(prefs, X100, "2", A, true));
        VivoNativeFullRes.noteHardware(prefs, "2", "the hardware-remosaic row gives a block 2 mosaic, not a Bayer", A);
        assertEquals("FULLRES 50 MP is off: modes 11, 31 — no frame.\nThe hardware remosaic (mode 0) — the wrong mosaic.",
                VivoNativeFullRes.status(prefs, X100, "2", A, true));
        assertNull("another firmware: nothing remembered", VivoNativeFullRes.status(prefs, X100, "2", B, true));

        // the X300 Ultra main: two sizes, its 200 MP row killed the camera, its 50 MP row 3 refused, row 1 next
        VivoNativeFullRes.noteRow(prefs, X300, "3", true, 1, 0, "camera error 4", A);
        assertEquals("FULLRES 200 MP is off: mode 0 — the camera failed.", VivoNativeFullRes.status(prefs, X300, "3", A, false));
        VivoNativeFullRes.noteRow(prefs, X300, "3", false, 1, 3, "session configuration failed", A);
        assertEquals("FULLRES 50 MP: mode 3 — the camera refused the session; next mode 1.\nFULLRES 200 MP is off: mode 0 — the camera failed.\n"
                        + "Mode 2 is the camera's own hardware remosaic: with “FULLRES: hardware remosaic” (Shooting) 50 MP shots are taken by it.",
                VivoNativeFullRes.status(prefs, X300, "3", A, false));
        // a build before the reasons: the rows tried, no reason
        prefs.edit().putInt("module_fullres_rowfail_trials", 1).putInt("module_fullres_rowfail_4_50", 2).commit();
        assertEquals("FULLRES 50 MP is off: modes 0, 4 — do not work.", VivoNativeFullRes.status(prefs, X300, "4", A, false));
        // the telephoto has no hardware-remosaic row: no hint
        VivoNativeFullRes.noteRow(prefs, X300, "5", false, 1, 1, NO_FRAME, A);
        // P229: the tele's own mosaic tries 1 / 27; row 14 is its hardware-remosaic row (rawr)
        assertEquals("FULLRES 50 MP: mode 1 — no frame; next mode 27.\nMode 14 is the camera's own hardware remosaic: with “FULLRES: hardware remosaic”"
                + " (Shooting) 50 MP shots are taken by it.", VivoNativeFullRes.status(prefs, X300, "5", A, false));
        // the X200 Ultra's rows are measured: no trials, no status
        prefs.edit().putInt("module_fullres_rowfail_5_200", 1).commit();
        assertNull(VivoNativeFullRes.status(prefs, X200, "5", A, false));
        assertNull(VivoNativeFullRes.status(prefs, null, "5", A, false));
    }

    @Test
    public void theOppoStatusNamesItsModes() {
        assertNull(OppoNativeFullRes.status(prefs, "2", A));
        OppoNativeFullRes.note(prefs, "2", 1, 35, "Native FULLRES mosaic does not match its sensor profile", A);
        assertEquals("its table not read in this run: the modes the reasons name", "FULLRES: mode 35 — the wrong mosaic.",
                OppoNativeFullRes.status(prefs, "2", A));
        OppoNativeFullRes.note(prefs, "2", 2, 37, "SDK FULLRES50 sensor mode is not active", A);
        assertEquals("FULLRES: mode 35 — the wrong mosaic; mode 37 — the mode did not switch on.", OppoNativeFullRes.status(prefs, "2", A));
        prefs.edit().putInt("module_oppo_fullres_fail_5", 2).commit();
        assertEquals("FULLRES: modes that do not work — 2.", OppoNativeFullRes.status(prefs, "5", A));
        // with its table: off once every candidate failed, else the next one
        Map<Integer, String> reasons = FullResTrials.reasons("35:mosaic,37:mosaic");
        assertEquals("FULLRES 50 MP is off: modes 35, 37 — the wrong mosaic.", FullResTrials.sizeLine(50, new int[]{35, 37}, 2, reasons));
        assertEquals("FULLRES 50 MP: mode 35 — the wrong mosaic; next mode 37.", FullResTrials.sizeLine(50, new int[]{35, 37}, 1, reasons));
        assertNull(FullResTrials.sizeLine(50, new int[]{35, 37}, 0, reasons));
    }

    /** Which size is off: the button stays, dimmed (P210: it was hidden). */
    @Test
    public void aSizeWhoseRowsAllFailedIsOffAndComesBackAfterTryAgain() {
        final String now = VivoNativeFullRes.stamp();
        assertTrue(VivoNativeFullRes.hasTable(X100, "4"));
        assertTrue(VivoNativeFullRes.hasTable(X100, "2"));
        assertFalse("mono", VivoNativeFullRes.hasTable(X100, "6"));
        assertFalse("the X200 Ultra keeps no trials", VivoNativeFullRes.hasTable(X200, "5"));
        assertFalse(VivoNativeFullRes.hasTable(null, "4"));
        // the live profile reads the trials of the phone it runs on (its failure keys)
        try (MockedStatic<VivoNativeFullRes> phone = mockStatic(VivoNativeFullRes.class, CALLS_REAL_METHODS)) {
            phone.when(VivoNativeFullRes::phone).thenReturn(X100);
            assertFalse(VivoNativeFullRes.sizeExhausted(X100, "4", true, false));
            VivoNativeFullRes.noteRow(prefs, X100, "4", true, 1, 0, NO_FRAME, now); // the tele's only 200 MP row
            assertTrue(VivoNativeFullRes.sizeExhausted(X100, "4", true, false));
            assertFalse("its 50 MP size still has rows", VivoNativeFullRes.sizeExhausted(X100, "4", false, false));
            assertFalse("one size: not a button of two", VivoNativeFullRes.sizeExhausted(X100, "2", false, false));
            FullResTrials.retry("4");
            assertFalse(VivoNativeFullRes.sizeExhausted(X100, "4", true, false));
            // the X300 main's 50 MP rows 3 / 1 failed: off, unless its hardware-remosaic row takes the 50 MP shots
            phone.when(VivoNativeFullRes::phone).thenReturn(X300);
            VivoNativeFullRes.noteRow(prefs, X300, "3", false, 2, 1, NO_FRAME, now);
            assertTrue(VivoNativeFullRes.sizeExhausted(X300, "3", false, false));
            assertFalse(VivoNativeFullRes.sizeExhausted(X300, "3", false, true));
            VivoNativeFullRes.noteHardware(prefs, "3", NO_FRAME, now);
            assertTrue(VivoNativeFullRes.sizeExhausted(X300, "3", false, true));
            assertFalse(VivoNativeFullRes.sizeExhausted(X300, "3", true, true));
            FullResTrials.retry("3");
            assertFalse(VivoNativeFullRes.sizeExhausted(X300, "3", false, false));
            assertFalse(VivoNativeFullRes.sizeExhausted(X300, "3", false, true));
        }
    }

    /** P200 suspends FULLRES for a 50 MP merge too: the switch and the shutter name the size being merged. */
    @Test
    public void theMergeTextNamesTheSizeBeingMerged() {
        assertEquals("A 50 MP shot is still being processed", PixelPro10.processingText(50));
        assertEquals("A 200 MP shot is still being processed", PixelPro10.processingText(200));
        assertEquals("A FULLRES shot is still being processed", PixelPro10.processingText(0));
        PixelPro10.suspendFullRes(true, 50);
        assertTrue(PixelPro10.fullResSuspended());
        assertEquals("A 50 MP shot is still being processed", PixelPro10.suspendedText());
        PixelPro10.suspendFullRes(true);
        assertEquals("A FULLRES shot is still being processed", PixelPro10.suspendedText());
        PixelPro10.suspendFullRes(false, 200);
        assertFalse(PixelPro10.fullResSuspended());
        assertEquals("A FULLRES shot is still being processed", PixelPro10.suspendedText());
    }

    @Test
    @Config(qualifiers = "ru")
    public void theTextsInRussian() {
        assertEquals("Снимок 50 Мп ещё обрабатывается", PixelPro10.processingText(50));
        assertEquals("Снимок 200 Мп ещё обрабатывается", PixelPro10.processingText(200));
        VivoNativeFullRes.noteRow(prefs, X100, "2", false, 2, 31, NO_FRAME, A);
        prefs.edit().putString("module_fullres_rowfail_2_50_r11_why", "11:noframe,31:noframe").commit();
        assertEquals("FULLRES 50 Мп выключен: режимы 11, 31 — нет кадра.\n"
                        + "Режим 0 — аппаратный ремозаик самой камеры: с «FULLRES: аппаратный ремозаик» (Съёмка) 50 Мп снимаются через него.",
                VivoNativeFullRes.status(prefs, X100, "2", A, false));
        VivoNativeFullRes.noteRow(prefs, X300, "5", false, 1, 1, "camera error 4", A);
        assertEquals("FULLRES 50 Мп: режим 1 — камера упала; дальше режим 27.\nРежим 14 — аппаратный ремозаик самой камеры: с «FULLRES: аппаратный ремозаик»"
                + " (Съёмка) 50 Мп снимаются через него.", VivoNativeFullRes.status(prefs, X300, "5", A, false));
    }
}
