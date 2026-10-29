package com.particlesdevs.photoncamera.capture;

import android.app.Application;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.util.Range;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** P27: the Hybrid's plan never throws and a frame that missed its plan keeps the role its measured exposure gives. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class HybridPlanTest {
    private MockedStatic<PreferenceKeys> prefs;
    /** Settings of the logs the plans below came from (before P68: Bento auto, 5 Shasta frames); empty = the app defaults. */
    private final java.util.Map<String, Float> pinned = new java.util.HashMap<>();
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> appStatic;

    @Before public void setup() {
        // PreferenceKeys' class initialisation reads strings through ScameraApp (as in CameraResumeTest).
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        com.particlesdevs.photoncamera.settings.SettingsManager settings = new com.particlesdevs.photoncamera.settings.SettingsManager(context);
        appStatic = mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getAppContext).thenReturn(context);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettingsManagerStatic).thenReturn(settings);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettings).thenReturn(new com.particlesdevs.photoncamera.api.Settings());
        appStatic.when(() -> com.particlesdevs.photoncamera.app.ScameraApp.getStringStatic(anyInt())).thenAnswer(i -> context.getString(i.getArgument(0)));
        prefs = mockStatic(PreferenceKeys.class);
        prefs.when(() -> PreferenceKeys.hybridSwitch(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        pinned.put("bento", 1f);
        pinned.put("shasta_frames", 5f);
        prefs.when(() -> PreferenceKeys.hybridValue(anyString(), anyFloat())).thenAnswer(i -> pinned.getOrDefault(i.<String>getArgument(0), i.getArgument(1)));
        prefs.when(PreferenceKeys::getAntibandingHz).thenReturn(0);
    }
    @After public void cleanup() { prefs.close(); appStatic.close(); }

    private static CameraCharacteristics camera(int isoMax) {
        CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)).thenReturn(new Range<>(10_000L, 500_000_000L));
        when(c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)).thenReturn(new Range<>(50, isoMax));
        return c;
    }
    private static CaptureRequest request(int index, ImageFrame.CaptureRole role) {
        CaptureRequest q = mock(CaptureRequest.class);
        when(q.getTag()).thenReturn(new ImageFrame.ScamCaptureTag(1, index, role));
        return q;
    }
    private static CaptureResult result(Long ns, Integer iso) {
        CaptureResult r = mock(CaptureResult.class);
        when(r.get(CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(ns);
        when(r.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(iso);
        return r;
    }

    @Test public void ownersLongFramesCappedAtTheNGainAreNFrames() {
        // 'LONG: ISO 320/640, shutter 10000000/10000000, 1.00 EV': the HAL capped the gain, the frame is an N exposure.
        HybridPlan plan = HybridPlan.build(10_000_000L, 320, 0f, camera(12000));
        assertEquals(3, plan.requests.size()); // the logs' 5 Shasta frames: at most 3 since P313
        HybridPlan.Request longFrame = plan.requests.get(0);
        assertEquals(ImageFrame.CaptureRole.LONG, longFrame.role);
        assertEquals(640, longFrame.iso);
        CaptureRequest q = request(0, ImageFrame.CaptureRole.LONG);
        assertEquals(ImageFrame.CaptureRole.NORMAL, plan.classify(q, result(10_000_000L, 320)));
        assertEquals(ImageFrame.CaptureRole.LONG, plan.classify(q, result(10_000_000L, 640)));
        assertEquals(ImageFrame.CaptureRole.LONG, plan.classify(q, result(10_000_000L, 560)));   // sensor rounding: planned role
        assertNull(plan.classify(q, result(10_000_000L, 416)));                                     // x1.3: no role fits
        assertNull(plan.classify(q, result(10_000_000L, null)));                                    // no metadata
        assertNull(plan.classify(request(9, ImageFrame.CaptureRole.LONG), result(10_000_000L, 640))); // outside the plan
    }

    @Test public void ultrashortRoundingKeepsItsRoleAndAFarMissIsReclassified() {
        HybridPlan plan = HybridPlan.build(10_000_000L, 320, 0.3f, camera(12000));
        HybridPlan.Request us = plan.requests.get(0);
        assertEquals(ImageFrame.CaptureRole.EXTRA_SHORT, us.role);
        CaptureRequest q = request(0, ImageFrame.CaptureRole.EXTRA_SHORT);
        // X100 Ultra log 9127: the HAL raised the ISO 50 -> 60 at a short shutter (+0.26 EV).
        assertEquals(ImageFrame.CaptureRole.EXTRA_SHORT, plan.classify(q, result(us.shutterNs, us.iso * 6 / 5)));
        assertEquals(ImageFrame.CaptureRole.EXTRA_SHORT, plan.classify(q, result(us.shutterNs, us.iso * 2)));
        assertEquals(ImageFrame.CaptureRole.NORMAL, plan.classify(q, result(10_000_000L, 300)));     // came back at N, darker
        assertNull(plan.classify(q, result(10_000_000L, 360)));                                     // brighter than N: never an N donor
    }

    @Test public void plansThatWorkedAreUnchanged() {
        // X200 Pro log 3813: N 10 ms ISO 659 -> LONG 10 ms ISO 1318 x5 (x3 since P313: «длинных достаточно 2-3, 5 не нужны»).
        HybridPlan day = HybridPlan.build(10_000_000L, 659, 0f, camera(12000));
        assertEquals(3, day.requests.size());
        for (HybridPlan.Request r : day.requests) { assertEquals(10_000_000L, r.shutterNs); assertEquals(1318, r.iso); }
        // X200 Pro log 9489: N 80 ms ISO 12000 -> LONG 125 ms ISO 12000.
        HybridPlan night = HybridPlan.build(80_000_000L, 12000, 0f, camera(12000));
        assertEquals(125_000_000L, night.requests.get(0).shutterNs);
        assertEquals(12000, night.requests.get(0).iso);
    }

    @Test public void learnedGainCapMovesTheBracketToTheShutter() {
        ExposureLimits.resetForTest();
        ExposureLimits limits = ExposureLimits.of("2", 4096, 3072, false);
        CaptureRequest q = mock(CaptureRequest.class);
        when(q.get(CaptureRequest.CONTROL_AE_MODE)).thenReturn(CaptureRequest.CONTROL_AE_MODE_OFF);
        when(q.get(CaptureRequest.SENSOR_EXPOSURE_TIME)).thenReturn(10_000_000L);
        when(q.get(CaptureRequest.SENSOR_SENSITIVITY)).thenReturn(640);
        // without a learned cap: today's plan (gain first)
        assertEquals(640, HybridPlan.build(10_000_000L, 320, 0f, camera(12000), limits).requests.get(0).iso);
        limits.observeManual(q, result(10_000_000L, 320));
        limits.observeManual(q, result(10_000_000L, 320));
        HybridPlan.Request r = HybridPlan.build(10_000_000L, 320, 0f, camera(12000), limits).requests.get(0);
        assertEquals(ImageFrame.CaptureRole.LONG, r.role);
        assertEquals(320, r.iso);
        assertEquals(20_000_000L, r.shutterNs);
    }

    /**
     * P163: the X300 Ultra's DCG session gives no gain below its floor (ISO 167 asked, 1336 given; the AE at 1339): the
     * ultrashort keeps the N gain and is short by the shutter, x1/8 of N; outside DCG the gain goes first as before.
     */
    @Test public void aDcgSessionTakesTheUltrashortFromTheShutter() {
        pinned.clear();
        HybridPlan dcg = HybridPlan.build(10_000_000L, 1339, 0.1f, camera(12000), null, true);
        HybridPlan.Request us = dcg.requests.get(0);
        assertEquals(ImageFrame.CaptureRole.EXTRA_SHORT, us.role);
        assertEquals(1339, us.iso);
        assertEquals(1_250_000L, us.shutterNs, 1_000L);
        assertEquals(0.125, us.ratio, 1e-3);
        assertTrue(dcg.description.contains("DCG"));
        HybridPlan plain = HybridPlan.build(10_000_000L, 1339, 0.1f, camera(12000), null, false);
        assertEquals("outside DCG the gain goes first", 167, plain.requests.get(0).iso);
        assertEquals(10_000_000L, plain.requests.get(0).shutterNs);
    }

    /**
     * P320 (owner: «с DCG света и тени должны тянуться так же хорошо как с применением сверхкороткого и длинного кадра»; DCG rules:
     * no exposure bracketing by default): a DCG shot with the app defaults (Bento «Всегда», 3 Shasta frames) takes no Shasta and
     * no ultrashort while the buffered DCG frame does not clip - the ring's N frames only; a measured clip still brings the
     * ultrashort (the X300 Ultra's DCG window clipped), never a long frame. A plain shot keeps both.
     */
    @Test public void aDcgShotTakesNoBracketsUnlessItsFrameClips() {
        pinned.clear();
        HybridPlan dcg = HybridPlan.build(10_000_000L, 400, 0f, camera(12000), null, true);
        assertEquals(1, dcg.requests.size());
        assertEquals(ImageFrame.CaptureRole.NORMAL, dcg.requests.get(0).role);
        assertTrue(dcg.description, dcg.description.contains("DCG: no shasta"));
        assertTrue(dcg.description, dcg.description.contains("DCG: highlights kept in the frame"));
        HybridPlan clipped = HybridPlan.build(10_000_000L, 400, 0.01f, camera(12000), null, true);
        for (HybridPlan.Request r : clipped.requests) assertNotEquals("never a long frame", ImageFrame.CaptureRole.LONG, r.role);
        assertEquals(ImageFrame.CaptureRole.EXTRA_SHORT, clipped.requests.get(0).role);
        HybridPlan plain = HybridPlan.build(10_000_000L, 400, 0f, camera(12000), null, false);
        int us = 0, longs = 0;
        for (HybridPlan.Request r : plain.requests) {
            if (r.role == ImageFrame.CaptureRole.EXTRA_SHORT) us++;
            if (r.role == ImageFrame.CaptureRole.LONG) longs++;
        }
        assertEquals("plain: Bento «Всегда»", 3, us);
        assertEquals("plain: 3 Shasta frames", 3, longs);
    }

    /** P320: a hand-set long shutter in a DCG session: the N frames only (no forced ultrashort, no Shasta); a plain one keeps both. */
    @Test public void aDcgManualLongShutterTakesNoBrackets() {
        pinned.clear();
        HybridPlan dcg = HybridPlan.manualLong(500_000_000L, 200, 0f, camera(12000), null, true);
        assertEquals(HybridPlan.MANUAL_NORMALS, dcg.requests.size());
        for (HybridPlan.Request r : dcg.requests) assertEquals(ImageFrame.CaptureRole.NORMAL, r.role);
        HybridPlan plain = HybridPlan.manualLong(500_000_000L, 200, 0f, camera(12000), null, false);
        assertTrue(plain.requests.size() > HybridPlan.MANUAL_NORMALS);
    }

    @Test public void p313AtMostThreeShastaFrames() {
        // owner, 2026-10-21: «длинных достаточно 2-3, 5 не нужны» - a stored 4 or 5 takes 3, 1..3 as set
        for (int set = 1; set <= 5; set++) {
            pinned.put("shasta_frames", (float) set);
            assertEquals(Math.min(set, 3), HybridPlan.bracketCount());
            int longs = 0;
            for (HybridPlan.Request r : HybridPlan.build(10_000_000L, 659, 0f, camera(12000)).requests)
                if (r.role == ImageFrame.CaptureRole.LONG) longs++;
            assertEquals(Math.min(set, 3), longs);
        }
    }

    @Test public void p68DefaultsTakeThreeUltrashortThenThreeShastaFrames() {
        // owner, 10 October 2026: Bento always with 3 frames, 3 Shasta frames; no clipping needed for Bento
        pinned.clear();
        HybridPlan plan = HybridPlan.build(10_000_000L, 659, 0f, camera(12000));
        assertEquals(6, plan.requests.size());
        for (int i = 0; i < 3; i++) assertEquals(ImageFrame.CaptureRole.EXTRA_SHORT, plan.requests.get(i).role);
        for (int i = 3; i < 6; i++) { assertEquals(ImageFrame.CaptureRole.LONG, plan.requests.get(i).role); assertEquals(1318, plan.requests.get(i).iso); }
    }

    /** P316: «Кадры светов: число» sets how many ultrashort frames are taken, with Mochi on too (it was raised to 3 and the extra dropped). */
    @Test public void bentoFramesAsSetWithMochiOn() {
        prefs.when(PreferenceKeys::getHybridMochi).thenReturn(2);
        pinned.put("bento", 2f);
        for (int set = 1; set <= 3; set++) {
            pinned.put("bento_frames", (float) set);
            assertEquals(set, HybridPlan.bentoFrames());
            int us = 0;
            for (HybridPlan.Request r : HybridPlan.build(10_000_000L, 659, 0f, camera(12000)).requests)
                if (r.role == ImageFrame.CaptureRole.EXTRA_SHORT) us++;
            assertEquals(set, us);
        }
    }

    @Test public void pixel7TakesBentoOnlyWhereTheFrameClips() {
        pinned.put("bento", 2f);
        assertEquals(ImageFrame.CaptureRole.EXTRA_SHORT, HybridPlan.build(10_000_000L, 659, 0f, camera(12000)).requests.get(0).role);
        org.robolectric.shadows.ShadowBuild.setManufacturer("Google");
        org.robolectric.shadows.ShadowBuild.setDevice("panther");
        HybridPlan clean = HybridPlan.build(10_000_000L, 659, 0f, camera(12000));
        for (HybridPlan.Request r : clean.requests) assertNotEquals(ImageFrame.CaptureRole.EXTRA_SHORT, r.role);
        assertEquals(ImageFrame.CaptureRole.EXTRA_SHORT, HybridPlan.build(10_000_000L, 659, 0.01f, camera(12000)).requests.get(0).role);
    }

    /** P79: the Redmi Note 11 Pro takes Bento "always" as auto too (weight-0 ultrashort frames cost every GPU pass there). */
    @Test public void redmiNote11ProTakesBentoOnlyWhereTheFrameClips() {
        pinned.put("bento", 2f);
        org.robolectric.shadows.ShadowBuild.setManufacturer("Xiaomi");
        org.robolectric.shadows.ShadowBuild.setDevice("viva");
        HybridPlan clean = HybridPlan.build(10_000_000L, 659, 0f, camera(12000));
        for (HybridPlan.Request r : clean.requests) assertNotEquals(ImageFrame.CaptureRole.EXTRA_SHORT, r.role);
        assertEquals(ImageFrame.CaptureRole.EXTRA_SHORT, HybridPlan.build(10_000_000L, 659, 0.01f, camera(12000)).requests.get(0).role);
    }

    @Test public void forcedShastaIgnoresTheRatioLimitButNotAFrameAtN() {
        pinned.put("shasta_ev", 4f);       // x16 of N
        pinned.put("shasta_max_ratio", 8f);
        assertTrue(HybridPlan.build(10_000_000L, 100, 0f, camera(12000)).description.contains("brackets skipped (ratio"));
        prefs.when(PreferenceKeys::getHybridShastaMode).thenReturn(2);
        HybridPlan forced = HybridPlan.build(10_000_000L, 100, 0f, camera(12000));
        assertEquals(3, forced.requests.size());
        for (HybridPlan.Request r : forced.requests) assertEquals(ImageFrame.CaptureRole.LONG, r.role);
        // the gain range ends at N and the shutter cannot grow (N at the handheld cap): no bracket even when forced
        HybridPlan atN = HybridPlan.build(125_000_000L, 12000, 0f, camera(12000));
        assertTrue(atN.description, atN.description.contains("brackets skipped (ratio 1.00)"));
    }

    @Test public void normalBackTakesNAfterTheShutterThenTheExtras() {
        HybridPlan plan = HybridPlan.buildNormalBack(10_000_000L, 20000, 0f, camera(12000), 4);
        for (int i = 0; i < 4; i++) {
            HybridPlan.Request r = plan.requests.get(i);
            assertEquals(ImageFrame.CaptureRole.NORMAL, r.role);
            assertEquals(12000, r.iso);
            assertEquals(1.0, r.ratio, 0.01);   // the shutter carries the exposure above the ISO range
        }
        assertTrue(plan.requests.size() > 4);
        for (int i = 4; i < plan.requests.size(); i++) assertNotEquals(ImageFrame.CaptureRole.NORMAL, plan.requests.get(i).role);
        assertEquals(ImageFrame.CaptureRole.NORMAL, plan.classify(request(0, ImageFrame.CaptureRole.NORMAL), result(16_666_667L, 12000)));
    }

    /** «Движение» after a shot (empty ring): X200 Ultra, the ceiling's 4 ms at ISO 20000, declared ISO up to 3200. */
    @Test public void motionNormalBackKeepsTheShortShutter() {
        HybridPlan plan = HybridPlan.buildNormalBack(4_000_000L, 20000, 0f, camera(3200), 4, null, 4_000_000L);
        for (int i = 0; i < 4; i++) {
            HybridPlan.Request r = plan.requests.get(i);
            assertEquals(4_000_000L, r.shutterNs);
            assertEquals(20000, r.iso); // the viewfinder's ISO, not the declared 3200 with a 25 ms shutter
            assertEquals(1.0, r.ratio, 0.01);
        }
        // a learned honoured cap lowers the ISO: the frame is darker (ratio < 1), the shutter stays within the ceiling
        ExposureLimits limits = new ExposureLimits("test");
        CaptureRequest q = mock(CaptureRequest.class);
        when(q.get(CaptureRequest.CONTROL_AE_MODE)).thenReturn(CaptureRequest.CONTROL_AE_MODE_OFF);
        when(q.get(CaptureRequest.SENSOR_SENSITIVITY)).thenReturn(20000);
        when(q.get(CaptureRequest.SENSOR_EXPOSURE_TIME)).thenReturn(4_000_000L);
        CaptureResult got = result(4_000_000L, 10000);
        limits.observeManual(q, got);
        limits.observeManual(q, got);
        HybridPlan capped = HybridPlan.buildNormalBack(4_000_000L, 20000, 0f, camera(3200), 4, limits, 4_000_000L);
        assertEquals(10000, capped.requests.get(0).iso);
        assertEquals(4_000_000L, capped.requests.get(0).shutterNs);
        assertEquals(0.5, capped.requests.get(0).ratio, 0.01);
        // outside the mode: as before (declared ISO, the shutter carries the rest)
        HybridPlan photo = HybridPlan.buildNormalBack(4_000_000L, 20000, 0f, camera(3200), 4, null, 0);
        assertEquals(3200, photo.requests.get(0).iso);
        assertEquals(25_000_000L, photo.requests.get(0).shutterNs);
    }

    /** P113 «Движение»: the ring's frames only, no request after the shutter (no flush, lead or AE restore frame to wait for). */
    @Test public void motionShotsTakeNothingAfterTheShutter() {
        HybridPlan plan = HybridPlan.zslOnly(4_000_000L, 20000);
        assertTrue(plan.requests.isEmpty());
        assertEquals(4_000_000L, plan.nShutterNs);
        assertEquals(20000, plan.nIso);
        assertTrue(plan.description, plan.description.contains("ring frames only"));
    }

    @Test public void noKnownExposureLetsTheCameraExpose() {
        HybridPlan plan = HybridPlan.buildNormalBack(0, 0, 0f, camera(12000), 4);
        assertEquals(4, plan.requests.size());
        for (HybridPlan.Request r : plan.requests) assertTrue(r.autoExposure);
        assertEquals(ImageFrame.CaptureRole.NORMAL, plan.classify(request(2, ImageFrame.CaptureRole.NORMAL), result(5_000_000L, 1234)));
        assertEquals(1, HybridPlan.single(0, 0).requests.size());
        assertFalse(HybridPlan.single(10_000_000L, 100).requests.get(0).autoExposure);
    }

    /** P84b (owner's 4 s shot, X200 Ultra 2026-10-11: N had been clamped to the declared 0.33 s). */
    @Test public void manualLongShutterKeepsItsShutter() {
        HybridPlan plan = HybridPlan.manualLong(4_000_000_000L, 72, 0f, null, null);
        assertEquals(HybridPlan.MANUAL_NORMALS, plan.requests.size()); // no characteristics: no Bento / Shasta either
        for (HybridPlan.Request r : plan.requests) {
            assertEquals(ImageFrame.CaptureRole.NORMAL, r.role);
            assertEquals(4_000_000_000L, r.shutterNs);
            assertEquals(72, r.iso);
        }
    }

    /** P84b: a Shasta frame of a long hand-set shutter takes its light from the shutter, the gain only past the ceiling. */
    @Test public void manualShastaTakesALongerShutterFirst() {
        HybridPlan.Request r = HybridPlan.manualShasta(4_000_000_000L, 72, 2.0, 32_000_000_000L, 12800);
        assertEquals(8_000_000_000L, r.shutterNs);
        assertEquals(72, r.iso);
        assertEquals(2.0, r.ratio, 1e-9);
        // 20 s x2 at a 32 s ceiling: 32 s, ISO 90 (x2.0)
        r = HybridPlan.manualShasta(20_000_000_000L, 72, 2.0, 32_000_000_000L, 12800);
        assertEquals(32_000_000_000L, r.shutterNs);
        assertEquals(90, r.iso);
        assertEquals(ImageFrame.CaptureRole.LONG, r.role);
    }

    /** P198: a 50 MP ZSL shot tops up its N after the shutter: N frames first, the plan's own roles after them in order. */
    @Test public void leadingNormalsComeFirstAtTheNExposure() {
        HybridPlan base = HybridPlan.single(10_000_000L, 200);
        HybridPlan more = base.withLeadingNormals(3);
        assertEquals(4, more.requests.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(com.particlesdevs.photoncamera.processing.ImageFrame.CaptureRole.NORMAL, more.requests.get(i).role);
            assertEquals(10_000_000L, more.requests.get(i).shutterNs);
            assertEquals(200, more.requests.get(i).iso);
        }
        assertSame(base, base.withLeadingNormals(0));
    }

    /**
     * P219 (owner, 2026-10-17: «сделай так чтобы брались все кадры, но для фулреза 1 длинный достаточно»): FULLRES asks for every
     * ultrashort frame and one long frame; 12 MP and a binned FULLRES burst («Наш ремозаик» off on a mosaic) keep 3 + 3.
     */
    @Test public void fullResTakesEveryUltrashortFrameAndOneLongFrame() {
        assertEquals("FULLRES Quad / Tetra with our remosaic", 1, HybridPlan.fullResLongFrames(true, 2, true));
        assertEquals(1, HybridPlan.fullResLongFrames(true, 4, true));
        assertEquals("a Bayer FULLRES stream (the HAL's remosaic) whatever the switch", 1, HybridPlan.fullResLongFrames(true, 1, false));
        assertEquals(1, HybridPlan.fullResLongFrames(true, 1, true));
        assertEquals("unknown block, remosaic on (the Pixel's max-resolution session)", 1, HybridPlan.fullResLongFrames(true, 0, true));
        // P217: a binned FULLRES burst («Наш ремозаик» off) takes one long frame too (the owner's «для фулреза 1 длинный достаточно»)
        assertEquals("binned FULLRES: one long frame", 1, HybridPlan.fullResLongFrames(true, 2, false));
        assertEquals(1, HybridPlan.fullResLongFrames(true, 4, false));
        assertEquals("unknown block, remosaic off (P217: one long frame as well)", 1, HybridPlan.fullResLongFrames(true, 0, false));
        assertEquals("12 MP", Integer.MAX_VALUE, HybridPlan.fullResLongFrames(false, 1, true));
        assertEquals(Integer.MAX_VALUE, HybridPlan.fullResLongFrames(false, 2, false));
        // the app defaults (P68): 3 ultrashort + 3 bracketed -> 3 + 1, the first bracketed request kept, the order unchanged
        pinned.clear();
        HybridPlan plain = HybridPlan.build(10_000_000L, 659, 0f, camera(12000));
        assertEquals(6, plain.requests.size());
        HybridPlan full = plain.withLongFrames(HybridPlan.fullResLongFrames(true, 2, true));
        assertEquals(4, full.requests.size());
        for (int i = 0; i < 3; i++) assertSame(plain.requests.get(i), full.requests.get(i));
        assertSame(plain.requests.get(3), full.requests.get(3));
        assertEquals(ImageFrame.CaptureRole.LONG, full.requests.get(3).role);
        assertTrue(full.description, full.description.startsWith(plain.description) && full.description.endsWith("FULLRES: 1 of 3 long frames"));
        assertEquals(plain.nShutterNs, full.nShutterNs);
        assertEquals(plain.nIso, full.nIso);
        // a still of the cut plan keeps its role by its own index
        assertEquals(ImageFrame.CaptureRole.LONG, full.classify(request(3, ImageFrame.CaptureRole.LONG), result(10_000_000L, 1318)));
        assertNull("the dropped bracketed requests are outside the plan", full.classify(request(4, ImageFrame.CaptureRole.LONG), result(10_000_000L, 1318)));
        assertEquals(ImageFrame.CaptureRole.EXTRA_SHORT, full.classify(request(0, ImageFrame.CaptureRole.EXTRA_SHORT),
                result(full.requests.get(0).shutterNs, full.requests.get(0).iso)));
        // nothing to cut: the same plan
        assertSame(plain, plain.withLongFrames(Integer.MAX_VALUE));
        assertSame(plain, plain.withLongFrames(3));
        HybridPlan single = HybridPlan.single(10_000_000L, 200);
        assertSame(single, single.withLongFrames(1));
        // the N topped up after the shutter (P198) come after the cut, the N exposure as before
        HybridPlan topped = full.withLeadingNormals(2);
        assertEquals(6, topped.requests.size());
        assertEquals(ImageFrame.CaptureRole.NORMAL, topped.requests.get(1).role);
        assertEquals(ImageFrame.CaptureRole.LONG, topped.requests.get(5).role);
        // a normal-back plan (FULLRES session without ring frames): its N and ultrashort frames stay
        HybridPlan back = HybridPlan.buildNormalBack(10_000_000L, 659, 0f, camera(12000), 4);
        HybridPlan backFull = back.withLongFrames(1);
        int n = 0, us = 0, longs = 0;
        for (HybridPlan.Request r : backFull.requests) {
            if (r.role == ImageFrame.CaptureRole.NORMAL) n++;
            if (r.role == ImageFrame.CaptureRole.EXTRA_SHORT) us++;
            if (r.role == ImageFrame.CaptureRole.LONG) longs++;
        }
        assertEquals(4, n);
        assertEquals(3, us);
        assertEquals(1, longs);
        // five Shasta frames (the pinned logs' setting: 3 since P313), Bento auto with a clipping ring frame: 3 + 1
        pinned.put("shasta_frames", 5f);
        HybridPlan five = HybridPlan.build(10_000_000L, 659, 0.01f, camera(12000)).withLongFrames(1);
        assertEquals(4, five.requests.size());
        assertTrue(five.description, five.description.endsWith("FULLRES: 1 of 3 long frames"));
    }

    /**
     * P409 (owner, 2026-10-29: «Нам нужно чтобы длинный давал больше информации в тенях»; the window burst: the room at 1-16 DN of
     * 1023 in N, one gain-first x2 frame worth 1.5-2.3 N frames there): deep shadows in the buffered frame take the bracketed frames
     * to +2 EV with their light from the shutter first (up to 1/60 s, within the handheld / motion cap), the gain part at most x2;
     * without them, or with the switch off, the plan is the one before.
     */
    @Test public void deepShadowsTakeLongerBracketedFrames() {
        pinned.put("shasta_frames", 3f);
        try {
            // a bright room under a window: N 2 ms ISO 100 -> 8 ms at ISO 100 (x4 of real light)
            HybridPlan.setDeepShadow(0.6f);
            HybridPlan p = HybridPlan.build(2_000_000L, 100, 0f, camera(6400));
            HybridPlan.Request l = longOf(p);
            assertEquals(p.description, 8_000_000L, l.shutterNs);
            assertEquals(100, l.iso);
            assertEquals(4.0, l.ratio, 1e-6);
            assertTrue(p.description, p.description.contains("deep shadows 60%: +2 EV"));
            // N 10 ms ISO 659: the shutter to 1/60 s, the gain x2 at most -> x3.33 (no longer shutter, no more gain)
            p = HybridPlan.build(10_000_000L, 659, 0f, camera(6400));
            l = longOf(p);
            assertEquals(16_666_667L, l.shutterNs);
            assertEquals(1318, l.iso);
            // N at 1/60 s already: the gain x2 as before (x2)
            l = longOf(HybridPlan.build(20_000_000L, 400, 0f, camera(6400)));
            assertEquals(20_000_000L, l.shutterNs);
            assertEquals(800, l.iso);
            // the HAL's motion cap holds the shutter
            HybridPlan.setMotionCap(5_000_000L);
            l = longOf(HybridPlan.build(2_000_000L, 100, 0f, camera(6400)));
            assertEquals(5_000_000L, l.shutterNs);
            assertEquals(160, l.iso);
            HybridPlan.setMotionCap(0);
            // mains light: N on whole 10 ms periods keeps the bracketed shutter on them (16.7 -> 10 ms, the gain x2)
            prefs.when(PreferenceKeys::getAntibandingHz).thenReturn(100);
            l = longOf(HybridPlan.build(10_000_000L, 659, 0f, camera(6400)));
            assertEquals(10_000_000L, l.shutterNs);
            assertEquals(1318, l.iso);
            prefs.when(PreferenceKeys::getAntibandingHz).thenReturn(0);
            // too few deep samples: as before (gain first, +1 EV)
            HybridPlan.setDeepShadow(0.1f);
            l = longOf(HybridPlan.build(2_000_000L, 100, 0f, camera(6400)));
            assertEquals(2_000_000L, l.shutterNs);
            assertEquals(200, l.iso);
            // the switch off: as before
            HybridPlan.setDeepShadow(0.6f);
            prefs.when(() -> PreferenceKeys.hybridSwitch(eq("shasta_deep"), anyBoolean())).thenReturn(false);
            l = longOf(HybridPlan.build(2_000_000L, 100, 0f, camera(6400)));
            assertEquals(2_000_000L, l.shutterNs);
            assertEquals(200, l.iso);
        } finally {
            HybridPlan.setDeepShadow(0f);
            HybridPlan.setMotionCap(0);
        }
    }

    /**
     * P410 (owner, 2026-10-29: «Делаем также динамический подбор количества длинных кадров от 1 до 3»): the bracketed frames by
     * the ring frame's shadow share - 1 under 10 %, 2 from 10 %, 3 from 30 % or with deep shadows - within the set count, at
     * most 2 under the HAL's motion; the set count when the share is unknown or the switch is off.
     */
    @Test public void bracketedFramesByTheShadows() {
        pinned.put("shasta_frames", 3f);
        try {
            assertEquals("unknown: the set count", 3, countLongs(HybridPlan.build(10_000_000L, 659, 0f, camera(6400))));
            HybridPlan.setShadow(0.05f);
            HybridPlan p = HybridPlan.build(10_000_000L, 659, 0f, camera(6400));
            assertEquals(1, countLongs(p));
            assertTrue(p.description, p.description.contains("shadows 5%: 1 of 3 long frames"));
            HybridPlan.setShadow(0.2f);
            assertEquals(2, countLongs(HybridPlan.build(10_000_000L, 659, 0f, camera(6400))));
            HybridPlan.setShadow(0.4f);
            assertEquals(3, countLongs(HybridPlan.build(10_000_000L, 659, 0f, camera(6400))));
            HybridPlan.setShadow(0.2f);
            HybridPlan.setDeepShadow(0.2f);
            assertEquals("deep shadows: 3", 3, countLongs(HybridPlan.build(10_000_000L, 659, 0f, camera(6400))));
            HybridPlan.setMotionCap(8_000_000L);
            assertEquals("motion: at most 2", 2, countLongs(HybridPlan.build(10_000_000L, 659, 0f, camera(6400))));
            HybridPlan.setMotionCap(0);
            pinned.put("shasta_frames", 2f);
            assertEquals("the set count is the maximum", 2, countLongs(HybridPlan.build(10_000_000L, 659, 0f, camera(6400))));
            pinned.put("shasta_frames", 3f);
            HybridPlan.setDeepShadow(0f);
            HybridPlan.setShadow(0.05f);
            prefs.when(() -> PreferenceKeys.hybridSwitch(eq("shasta_auto_count"), anyBoolean())).thenReturn(false);
            assertEquals("the switch off: the set count", 3, countLongs(HybridPlan.build(10_000_000L, 659, 0f, camera(6400))));
        } finally {
            HybridPlan.setShadow(Float.NaN);
            HybridPlan.setDeepShadow(0f);
            HybridPlan.setMotionCap(0);
        }
    }

    private static int countLongs(HybridPlan p) {
        int n = 0;
        for (HybridPlan.Request r : p.requests) if (r.role == ImageFrame.CaptureRole.LONG) n++;
        return n;
    }

    private static HybridPlan.Request longOf(HybridPlan p) {
        for (HybridPlan.Request r : p.requests) if (r.role == ImageFrame.CaptureRole.LONG) return r;
        throw new AssertionError("no long frame: " + p.description);
    }
}
