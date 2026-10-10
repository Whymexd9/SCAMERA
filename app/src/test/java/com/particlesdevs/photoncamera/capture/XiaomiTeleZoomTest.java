package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class XiaomiTeleZoomTest {
    private static final float E = 1e-4f;
    private static final float USER_75 = 75f / (100f / 4.30000019f);

    @Test
    public void ownersValuesAt100mmAndTheOpticalEnds() {
        XiaomiTeleZoom.Plan p = XiaomiTeleZoom.plan(100f, false);
        assertEquals(4.30000019f, p.userZoom, E);
        assertEquals(1.34375f, p.zoomRatio, E);
        assertEquals(1f, p.residual, E);
        assertEquals(100f, p.opticalMm, E);
        assertFalse(p.isz);
        XiaomiTeleZoom.Plan w = XiaomiTeleZoom.plan(75f, false);
        assertEquals(USER_75, w.userZoom, E);
        assertEquals(1.0078f, w.zoomRatio, 1e-3f);
        assertEquals("below the tele's widest: held at 75 mm", 75f, XiaomiTeleZoom.plan(60f, false).mm, E);
        assertEquals("the top of the range: 400 mm like the stock dial's 17.2x", 400f, XiaomiTeleZoom.plan(900f, true).mm, E);
    }

    @Test
    public void between100And150TheOpticsStayAt100AndTheFrameIsCropped() {
        XiaomiTeleZoom.Plan p = XiaomiTeleZoom.plan(125f, false);
        assertEquals(4.30000019f, p.userZoom, E);
        assertEquals(1.25f, p.residual, E);
        assertEquals(1.34375f * 1.25f, p.zoomRatio, E);
        assertFalse(p.isz);
    }

    @Test
    public void at150TheOpticsGoBackTo75AndIszTurnsOnInTheSameRequest() {
        XiaomiTeleZoom.Plan below = XiaomiTeleZoom.plan(149.9f, false);
        assertFalse(below.isz);
        assertEquals(100f, below.opticalMm, E);
        XiaomiTeleZoom.Plan on = XiaomiTeleZoom.plan(150f, false);
        assertTrue(on.isz);
        assertEquals("owner: at 150 mm the optics return to their initial value", USER_75, on.userZoom, E);
        assertEquals(75f, on.opticalMm, E);
        assertEquals("the 75 mm zoomRatio as well", XiaomiTeleZoom.plan(75f, false).zoomRatio, on.zoomRatio, E);
        assertEquals(1f, on.residual, E);
        assertEquals("RAW frame = 75 mm x 2", 150f, on.rawMm, E);
        // 150-200 mm: the optical 75-100 mm again, inside ISZ
        XiaomiTeleZoom.Plan mid = XiaomiTeleZoom.plan(175f, true);
        assertEquals(87.5f, mid.opticalMm, E);
        assertEquals(XiaomiTeleZoom.plan(87.5f, false).userZoom, mid.userZoom, E);
        assertEquals(XiaomiTeleZoom.plan(87.5f, false).zoomRatio, mid.zoomRatio, E);
        XiaomiTeleZoom.Plan top = XiaomiTeleZoom.plan(200f, true);
        assertEquals(4.30000019f, top.userZoom, E);
        assertEquals(1.34375f, top.zoomRatio, E);
        assertEquals(1f, top.residual, E);
        assertEquals("above 200 mm a crop of the ISZ frame", 1.2f, XiaomiTeleZoom.plan(240f, true).residual, E);
        assertEquals(2f, XiaomiTeleZoom.plan(400f, true).residual, E);
    }

    @Test
    public void theReverseHappensAtTheSamePoint() {
        assertFalse("not yet on below 150", XiaomiTeleZoom.plan(149.5f, false).isz);
        assertTrue("stays on at 149.5 (1 mm band against flicker)", XiaomiTeleZoom.plan(149.5f, true).isz);
        assertFalse("off below 149", XiaomiTeleZoom.plan(148.9f, true).isz);
        XiaomiTeleZoom.Plan off = XiaomiTeleZoom.plan(148.9f, true);
        assertEquals("back at the 100 mm optics with the crop", 100f, off.opticalMm, E);
        assertEquals(1.489f, off.residual, 1e-3f);
        // inside the band the ISZ frame is held at its widest (150 mm): at most a 0.7 % step
        XiaomiTeleZoom.Plan held = XiaomiTeleZoom.plan(149.2f, true);
        assertTrue(held.isz);
        assertEquals(150f, held.rawMm * held.residual, 0.01f);
    }

    @Test
    public void aToggleWaitsForTheDebounceUnlessTheZoomIsFarPast() {
        assertTrue(XiaomiTeleZoom.nextIsz(151f, false, 10_000));
        assertFalse("just toggled off: on again only after TOGGLE_MS", XiaomiTeleZoom.nextIsz(151f, false, 100));
        assertTrue("far past the switch it does not wait", XiaomiTeleZoom.nextIsz(170f, false, 100));
        assertTrue("just toggled on: stays on", XiaomiTeleZoom.nextIsz(147f, true, 100));
        assertFalse(XiaomiTeleZoom.nextIsz(147f, true, XiaomiTeleZoom.TOGGLE_MS));
        assertFalse(XiaomiTeleZoom.nextIsz(130f, true, 100));
    }

    /** The output field of view (as the HAL model of the 17U recording delivers it) never jumps over the whole slider. */
    @Test
    public void theFieldOfViewIsContinuousOverTheWholeSliderBothWays() {
        // lens following the commands (optical zoom works)
        assertContinuous(0f);
        // lens standing at 75 mm (crop mode)
        assertContinuous(75f);
    }

    private static void assertContinuous(float fixedLens) {
        boolean isz = false;
        float prev = Float.NaN;
        for (int dir = 0; dir < 2; dir++) {
            for (int i = 0; i <= 3250; i++) {
                float mm = dir == 0 ? 75f + i * 0.1f : 400f - i * 0.1f;
                isz = XiaomiTeleZoom.nextIsz(mm, isz);
                XiaomiTeleZoom.Plan p = XiaomiTeleZoom.planFor(mm, isz, fixedLens);
                float lens = fixedLens > 0f ? fixedLens : p.opticalMm;
                float preview = XiaomiTeleZoom.halFieldOfView(p, lens);
                float shot = p.rawMm * p.residual;
                assertEquals("shot = preview at " + mm + (fixedLens > 0 ? " (crop mode)" : ""), preview, shot, 0.01f * preview);
                assertEquals("the output is the slider's focal length at " + mm, Math.max(mm, isz ? 150f : 75f), preview, 0.011f * mm);
                if (!Float.isNaN(prev)) assertTrue("jump at " + mm + ": " + prev + " -> " + preview, Math.abs(preview / prev - 1f) < 0.008f);
                prev = preview;
            }
        }
    }

    @Test
    public void theOldMappingWithAStillLensIsWhatTheRecordingShowed() {
        // The 17U recording: no zoom 75-100 mm, crop to ~1.5x the lens at 150 mm, x1.33 jump into ISZ, nothing 150-200 mm.
        float lens = 78f;
        float at75 = XiaomiTeleZoom.halFieldOfView(XiaomiTeleZoom.planFor(75f, false, 0f), lens);
        float at100 = XiaomiTeleZoom.halFieldOfView(XiaomiTeleZoom.planFor(100f, false, 0f), lens);
        float at149 = XiaomiTeleZoom.halFieldOfView(XiaomiTeleZoom.planFor(149f, false, 0f), lens);
        float at200 = XiaomiTeleZoom.halFieldOfView(XiaomiTeleZoom.planFor(200f, true, 0f), lens);
        assertEquals(at75, at100, 0.01f);
        assertEquals(1.49f, at149 / at75, 0.01f);
        // crop mode makes the same still lens give the slider's values
        assertEquals(100f, XiaomiTeleZoom.halFieldOfView(XiaomiTeleZoom.planFor(100f, false, lens), lens), 0.01f);
        assertEquals(200f, XiaomiTeleZoom.halFieldOfView(XiaomiTeleZoom.planFor(200f, true, lens), lens), 0.01f);
        assertTrue(at200 < 160f);
    }

    @Test
    public void cropModeTellsTheHalTheLensPosition() {
        XiaomiTeleZoom.Plan p = XiaomiTeleZoom.planFor(120f, false, 75f);
        assertEquals(USER_75, p.userZoom, E);
        assertEquals(120f / (100f / 1.34375f), p.zoomRatio, E);
        assertEquals(1.6f, p.residual, E);
        XiaomiTeleZoom.Plan z = XiaomiTeleZoom.planFor(180f, true, 75f);
        assertEquals(USER_75, z.userZoom, E);
        assertEquals(1.2f, z.residual, E);
    }

    /** Build 30595 recording: crop mode (lens at 75 mm) gives the slider's field of view, and the RAW viewfinder crops the same. */
    @Test
    public void cropModeFieldOfViewAndTheRawViewfinderCrop() {
        for (float mm = 75f; mm <= 400f; mm += 5f) {
            boolean isz = mm >= XiaomiTeleZoom.ISZ_ON;
            XiaomiTeleZoom.Plan p = XiaomiTeleZoom.planFor(mm, isz, 75f);
            assertEquals("HAL field of view at " + mm, mm, XiaomiTeleZoom.halFieldOfView(p, 75f), 0.01f * mm);
            // the RAW frame is the lens (x2 in ISZ); the viewfinder crops it to the HAL's preview
            assertEquals("RAW viewfinder at " + mm, mm, 75f * (isz ? 2f : 1f) * XiaomiTeleZoom.cropOf(p), 0.01f * mm);
            assertEquals("the shot crops the same", XiaomiTeleZoom.cropOf(p), p.residual, 0.001f);
        }
        assertEquals(1f, XiaomiTeleZoom.cropOf(null), E);
    }

    /**
     * Build 30607 next to the stock camera: by default no forced ISZ, one continuous zoomRatio crop of the 75 mm lens from
     * 75 to 400 mm (ISP preview throughout, no colour change), inside the HAL's zoomRatio range.
     */
    @Test
    public void withoutTheForcedIszTheWholeSliderIsOneContinuousCrop() {
        float prev = Float.NaN;
        for (float mm = 75f; mm <= 400f; mm += 0.5f) {
            XiaomiTeleZoom.Plan p = XiaomiTeleZoom.planFor(mm, false, 75f);
            assertFalse(p.isz);
            assertEquals(USER_75, p.userZoom, E);
            assertEquals("HAL field of view at " + mm, mm, XiaomiTeleZoom.halFieldOfView(p, 75f), 0.01f * mm);
            assertEquals("shot crop at " + mm, mm / 75f, p.residual, 1e-3f);
            assertTrue("zoomRatio inside [1, 13.4375] at " + mm, p.zoomRatio >= 1f && p.zoomRatio <= 13.4375f);
            float fov = XiaomiTeleZoom.halFieldOfView(p, 75f);
            if (!Float.isNaN(prev)) assertTrue("jump at " + mm, Math.abs(fov / prev - 1f) < 0.008f);
            prev = fov;
        }
        assertNull("no forced mode without ISZ", XiaomiTeleZoom.modeFor(false, false, 4));
    }

    /** Build 30595 recording: the sensor stayed in mode 9 after ISZ off until a restart; the way back names the old mode. */
    @Test
    public void afterIszTheRequestNamesTheModeToGoBackTo() {
        assertEquals(Integer.valueOf(9), XiaomiTeleZoom.modeFor(true, false, null));
        assertEquals(Integer.valueOf(9), XiaomiTeleZoom.modeFor(true, true, 4));
        assertNull("before any ISZ the HAL keeps its own mode", XiaomiTeleZoom.modeFor(false, false, 4));
        assertEquals(Integer.valueOf(4), XiaomiTeleZoom.modeFor(false, true, 4));
        assertNull("unknown normal mode: as before", XiaomiTeleZoom.modeFor(false, true, null));
        assertNull("never 9 as the normal mode", XiaomiTeleZoom.modeFor(false, true, 9));
        // frames requested with the normal mode belong to the non-ISZ state
        assertTrue(XiaomiTeleZoom.frameReady(4, 4, false, 2, 1));
        assertFalse(XiaomiTeleZoom.frameReady(4, 9, false, 2, 1));
    }

    @Test
    public void theLensIsDeclaredFixedOnlyWhenItNeverFollows() {
        assertFalse("not yet long enough", XiaomiTeleZoom.lensDoesNotFollow(100f, 75f, 500, false));
        assertTrue(XiaomiTeleZoom.lensDoesNotFollow(100f, 75f, XiaomiTeleZoom.FOLLOW_MS, false));
        assertFalse("a lens that moved once is trusted", XiaomiTeleZoom.lensDoesNotFollow(100f, 75f, 5000, true));
        assertFalse("close enough", XiaomiTeleZoom.lensDoesNotFollow(79f, 75f, 5000, false));
        assertFalse("nothing reported", XiaomiTeleZoom.lensDoesNotFollow(100f, Float.NaN, 5000, false));
        assertEquals(100f, XiaomiTeleZoom.equivalentOf(26.533f, 19.9f), 0.01f);
    }

    @Test
    public void reportsThatCannotBeTheTeleLensDoNotCount() {
        assertEquals(87.5f, XiaomiTeleZoom.lensPosition(87.5f, false), E);
        assertEquals("ISZ may report the 2x field of view", 87.5f, XiaomiTeleZoom.lensPosition(175f, true), E);
        assertTrue("another camera's focal length (logical result)", Float.isNaN(XiaomiTeleZoom.lensPosition(30f, false)));
        assertTrue("above the lens range outside ISZ", Float.isNaN(XiaomiTeleZoom.lensPosition(175f, false)));
        assertTrue(Float.isNaN(XiaomiTeleZoom.lensPosition(Float.NaN, true)));
        // frames of the other mode and the first TOGGLE_MS after a toggle are not counted
        assertTrue(XiaomiTeleZoom.countsForFollow(null, false, 10_000));
        assertTrue(XiaomiTeleZoom.countsForFollow(9, true, 10_000));
        assertFalse(XiaomiTeleZoom.countsForFollow(null, true, 10_000));
        assertFalse(XiaomiTeleZoom.countsForFollow(9, false, 10_000));
        assertFalse(XiaomiTeleZoom.countsForFollow(9, true, XiaomiTeleZoom.TOGGLE_MS - 1));
    }

    @Test
    public void framesOfTheOldModeAreHeldBackAfterAnInSessionSwitch() {
        // switched ISZ on: a frame requested without the mode is old
        assertFalse(XiaomiTeleZoom.frameReady(null, null, true, 1, 0));
        // requested with mode 9, the result reports the old mode yet (sensor mode lags the request)
        assertFalse(XiaomiTeleZoom.frameReady(9, 0, true, 3, 1));
        assertTrue(XiaomiTeleZoom.frameReady(9, 9, true, 4, 2));
        // no mode in the results: a few frames after the first matching request
        assertFalse(XiaomiTeleZoom.frameReady(9, null, true, 3, 2));
        assertTrue(XiaomiTeleZoom.frameReady(9, null, true, 5, XiaomiTeleZoom.BARRIER_BLIND_FRAMES));
        // switched off
        assertFalse(XiaomiTeleZoom.frameReady(9, 9, false, 2, 0));
        assertTrue(XiaomiTeleZoom.frameReady(null, 0, false, 2, 1));
        // never more than BARRIER_MAX_FRAMES frames
        assertTrue(XiaomiTeleZoom.frameReady(null, null, true, XiaomiTeleZoom.BARRIER_MAX_FRAMES, 0));
    }

    private static final float[] REAL = XiaomiTeleZoom.REAL_RANGE, UI = XiaomiTeleZoom.UI_RANGE;

    /** 17U dumps 2026-10-08: opticalZoomCurrentRatio 3.40042..3.40052 and target 3.4 in every dump = the lens at its wide end. */
    @Test
    public void theHalsOpticalReportOfTheDumpsIsTheWideEnd() {
        assertEquals("UI 3.2 x 23.256 mm", 74.42f, XiaomiTeleZoom.opticalMmOf(3.40046835f, REAL, UI), 0.02f);
        assertEquals(74.42f, XiaomiTeleZoom.opticalMmOf(3.40000010f, REAL, UI), 0.02f);
        assertEquals("the long end: 4.3 = 100 mm", 100f, XiaomiTeleZoom.opticalMmOf(4.30000019f, REAL, UI), 0.01f);
        assertEquals("half way: UI 3.75", 3.75f * (100f / 4.30000019f), XiaomiTeleZoom.opticalMmOf(3.85f, REAL, UI), 0.01f);
        assertTrue("not this lens", Float.isNaN(XiaomiTeleZoom.opticalMmOf(1f, REAL, UI)));
        assertTrue(Float.isNaN(XiaomiTeleZoom.opticalMmOf(6f, REAL, UI)));
        assertTrue(Float.isNaN(XiaomiTeleZoom.opticalMmOf(Float.NaN, REAL, UI)));
        assertEquals("a little outside the range: clamped to the end", 100f, XiaomiTeleZoom.opticalMmOf(4.35f, REAL, UI), 0.01f);
    }

    /** The request's opticalZoomTargetRatio: the HAL's own UI -> optical map (smartFOV 3.2 -> 3.4, 4.3 -> 4.3) of userZoomRatio. */
    @Test
    public void theTargetOfACommandedPositionIsOnTheHalsScale() {
        assertEquals(4.3f, XiaomiTeleZoom.halRatioOf(100f, REAL, UI), 1e-4f);
        assertEquals("75 mm = userZoomRatio 3.225", 3.4f + 0.025f / 1.1f * 0.9f, XiaomiTeleZoom.halRatioOf(75f, REAL, UI), 1e-4f);
        assertEquals("never below the optics' wide end", 3.4f, XiaomiTeleZoom.halRatioOf(60f, REAL, UI), 1e-4f);
        assertEquals("nor past the long end", 4.3f, XiaomiTeleZoom.halRatioOf(150f, REAL, UI), 1e-4f);
        for (float mm = 75f; mm <= 100f; mm += 0.5f) {
            XiaomiTeleZoom.Plan p = XiaomiTeleZoom.planFor(mm, false, 0f);
            float target = XiaomiTeleZoom.halRatioOf(p.userZoom * XiaomiTeleZoom.MM_PER_USER, REAL, UI);
            assertEquals("round trip at " + mm, mm, XiaomiTeleZoom.opticalMmOf(target, REAL, UI), 0.01f);
        }
        // the lens standing at 75 mm (crop mode) names its own position
        assertEquals(XiaomiTeleZoom.halRatioOf(75f, REAL, UI),
                XiaomiTeleZoom.halRatioOf(XiaomiTeleZoom.planFor(180f, true, 75f).userZoom * XiaomiTeleZoom.MM_PER_USER, REAL, UI), 1e-5f);
    }

    /**
     * Build b087541 on the 17U: the focal length followed userZoomRatio to 100 mm, the preview did not zoom. The HAL's optical
     * report (3.40 = the wide end) shows the glass did not move, so the follow check now goes to crop mode; with
     * xiaomi_hal_optics 0 (the focal length, as before) it never did.
     */
    @Test
    public void theFollowCheckReadsTheGlassNotTheClaim() {
        final float fMin = 19.9f;
        final Float claimed100 = 26.533f, halWide = 3.40046835f;
        float glass = XiaomiTeleZoom.lensMmFor(halWide, true, REAL, UI, claimed100, fMin, false);
        assertEquals(74.42f, glass, 0.02f);
        assertTrue("never moved, 25 mm short for FOLLOW_MS: crop mode",
                XiaomiTeleZoom.lensDoesNotFollow(100f, glass, XiaomiTeleZoom.FOLLOW_MS, false));
        float claim = XiaomiTeleZoom.lensMmFor(halWide, false, REAL, UI, claimed100, fMin, false);
        assertEquals("xiaomi_hal_optics 0: the focal length as before", 100f, claim, 0.01f);
        assertFalse(XiaomiTeleZoom.lensDoesNotFollow(100f, claim, XiaomiTeleZoom.FOLLOW_MS, false));
        assertEquals("no HAL report: the focal length", 100f, XiaomiTeleZoom.lensMmFor(null, true, REAL, UI, claimed100, fMin, false), 0.01f);
        assertEquals("a HAL report off this lens: the focal length", 100f,
                XiaomiTeleZoom.lensMmFor(1f, true, REAL, UI, claimed100, fMin, false), 0.01f);
        assertTrue("nothing at all", Float.isNaN(XiaomiTeleZoom.lensMmFor(null, true, REAL, UI, null, fMin, false)));
        assertEquals("the HAL report alone is enough", 100f, XiaomiTeleZoom.lensMmFor(4.3f, true, REAL, UI, null, 0f, false), 0.01f);
        // glass that follows: trusted, no crop mode
        float moved = XiaomiTeleZoom.lensMmFor(4.29f, true, REAL, UI, claimed100, fMin, false);
        assertFalse(XiaomiTeleZoom.lensDoesNotFollow(100f, moved, XiaomiTeleZoom.FOLLOW_MS, false));
        // in ISZ the report is still the lens position (never doubled)
        assertEquals(100f, XiaomiTeleZoom.lensMmFor(4.3f, true, REAL, UI, 53.07f, fMin, true), 0.01f);
    }

    @Test
    public void theHalRangesFallBackToThe17UValues() {
        assertTrue(java.util.Arrays.equals(REAL, XiaomiTeleZoom.rangeOr(null, REAL)));
        assertTrue(java.util.Arrays.equals(REAL, XiaomiTeleZoom.rangeOr(new float[]{4.3f, 3.4f}, REAL)));
        assertTrue(java.util.Arrays.equals(REAL, XiaomiTeleZoom.rangeOr(new float[]{3.4f}, REAL)));
        assertTrue(java.util.Arrays.equals(new float[]{3.5f, 4.5f}, XiaomiTeleZoom.rangeOr(new float[]{3.5f, 4.5f, 0f}, REAL)));
    }

    @Test
    public void theOpticalReportIsLoggedOnlyWhenItChanges() {
        assertTrue("the first report", XiaomiTeleZoom.opticsLogDue(Float.NaN, 74.4f, Float.NaN, 3.4f, Integer.MIN_VALUE, 0));
        assertFalse("the same", XiaomiTeleZoom.opticsLogDue(74.4f, 74.6f, 3.4f, 3.405f, 0, 0));
        assertTrue("the lens by 1 mm", XiaomiTeleZoom.opticsLogDue(74.4f, 75.5f, 3.4f, 3.4f, 0, 0));
        assertTrue("a new target", XiaomiTeleZoom.opticsLogDue(74.4f, 74.4f, 3.4f, 3.6f, 0, 0));
        assertTrue("the state", XiaomiTeleZoom.opticsLogDue(74.4f, 74.4f, 3.4f, 3.4f, 0, 1));
        assertFalse("nothing reported twice", XiaomiTeleZoom.opticsLogDue(Float.NaN, Float.NaN, Float.NaN, Float.NaN, -1, -1));
    }

    @Test
    public void theZoomLadderReadsLikeTheStockDial() {
        assertEquals("3.2×", XiaomiTeleZoom.label(XiaomiTeleZoom.STOCK_RATIO));
        float[] stops = XiaomiTeleZoom.stopsFor(XiaomiTeleZoom.STOCK_RATIO);
        assertEquals("4.3×", XiaomiTeleZoom.label(stops[0]));
        assertEquals("8.6×", XiaomiTeleZoom.label(stops[1]));
        assertEquals("17.2×", XiaomiTeleZoom.label(stops[2]));
        assertTrue(XiaomiTeleZoom.isAutoTeleRatio(3.3f));
        assertFalse(XiaomiTeleZoom.isAutoTeleRatio(1f));
        assertFalse(XiaomiTeleZoom.isAutoTeleRatio(6.7f));
        // not this phone (unit test): nothing changes
        assertEquals(3.3f, XiaomiTeleZoom.stockRatio(3.3f), E);
        assertEquals("3.3×", XiaomiTeleZoom.stockLabel("3.3×"));
        assertEquals(0f, XiaomiTeleZoom.maxZoom(3.3f), E);
        assertTrue(XiaomiTeleZoom.stops(java.util.Arrays.asList(1f, 3.3f)).isEmpty());
        assertNull(XiaomiTeleZoom.stockLabel(null));
    }

    @Test
    public void lensDrivenAwayFromTheCommandIsNotAMove() {
        // 17U log 2026-10-08: 88.7 -> 84.8 -> 79.0 -> 74.9 mm while 100 mm was commanded (the HAL's own target 74.4 mm)
        float[] steps = {88.7f, 84.8f, 79.0f, 74.9f};
        for (int i = 1; i < steps.length; ++i)
            assertFalse(XiaomiTeleZoom.movedToward(100f, steps[i - 1], steps[i]));
        assertTrue("toward the command", XiaomiTeleZoom.movedToward(100f, 75f, 80f));
        assertFalse("jitter", XiaomiTeleZoom.movedToward(100f, 75f, 75.6f));
    }

    /**
     * P41b, the owner's stock capture 2026-10-09 (userZoomRatio -> opticalZoomTargetRatio of the HAL): 3.2 -> 3.40,
     * 3.54 -> 3.68, 3.88 -> 3.96, 4.16 -> 4.19, 4.3 -> 4.30, 5.02 / 6.0 -> 4.30 (held, crop above).
     */
    @Test
    public void theStockDialMapsToTheHalsOpticalTarget() {
        float[][] stock = {{3.2f, 3.40f}, {3.54f, 3.68f}, {3.88f, 3.96f}, {4.16f, 4.19f}, {4.3f, 4.30f}, {5.02f, 4.30f}, {6.0f, 4.30f}};
        for (float[] s : stock)
            assertEquals("UI " + s[0], s[1], XiaomiTeleZoom.stockOpticalTarget(s[0], REAL, UI), 0.006f);
    }

    /** P41b: on the logical camera zoomRatio = userZoomRatio = the dial, the photo is cropped by what the tele's RAW lacks. */
    @Test
    public void theLogicalCameraGetsTheDialAndThePhotoTheRest() {
        XiaomiTeleZoom.Plan p = XiaomiTeleZoom.planLogical(100f, Float.NaN, false, 100f);
        assertTrue(p.logical);
        assertEquals("the stock camera's 4.3", 4.30000019f, p.zoomRatio, E);
        assertEquals(p.zoomRatio, p.userZoom, E);
        assertEquals("the lens due at 100 mm: no crop", 1f, p.residual, E);
        assertEquals(100f, p.rawMm, E);
        // 75-100 mm: the HAL's optics, nothing left for the photo when the lens follows
        for (float mm = 75f; mm <= 100f; mm += 5f) {
            XiaomiTeleZoom.Plan o = XiaomiTeleZoom.planLogical(mm, mm, false, 100f);
            assertEquals("at " + mm, 1f, o.residual, E);
            assertEquals(mm / XiaomiTeleZoom.MM_PER_USER, o.zoomRatio, E);
        }
        // the lens pinned at 74.4 mm by the HAL (third-party pipeline): the photo crops the rest
        XiaomiTeleZoom.Plan pinned = XiaomiTeleZoom.planLogical(100f, 74.4f, false, 100f);
        assertEquals("clamped to the lens range", 75f, pinned.rawMm, E);
        assertEquals(100f / 75f, pinned.residual, 1e-3f);
        assertEquals("the request does not change", 4.30000019f, pinned.zoomRatio, E);
        // above 100 mm: optics held, crop
        XiaomiTeleZoom.Plan crop = XiaomiTeleZoom.planLogical(150f, 100f, false, 100f);
        assertEquals(100f, crop.opticalMm, E);
        assertEquals(1.5f, crop.residual, E);
        // the HAL's mode 9 at ~8.5x (stock): the RAW frame is 2x the lens
        XiaomiTeleZoom.Plan isz = XiaomiTeleZoom.planLogical(400f, 100f, true, 100f);
        assertEquals(200f, isz.rawMm, E);
        assertEquals(2f, isz.residual, E);
        assertEquals("17.2x, inside ExtendedMaxZoom", 400f / XiaomiTeleZoom.MM_PER_USER, isz.zoomRatio, E);
        XiaomiTeleZoom.Plan atIsz = XiaomiTeleZoom.planLogical(8.5f * XiaomiTeleZoom.MM_PER_USER, 100f, true, 100f);
        assertEquals("the stock switch at 8.5x: never below 1", 1f, atIsz.residual, E);
        // without ExtendedMaxZoom: the logical camera's 10x, the dial still in userZoomRatio
        XiaomiTeleZoom.Plan limited = XiaomiTeleZoom.planLogical(300f, 100f, true, 10f);
        assertEquals(10f, limited.zoomRatio, E);
        assertEquals(300f / XiaomiTeleZoom.MM_PER_USER, limited.userZoom, E);
        assertEquals("below the tele's widest: held at 75 mm", 75f, XiaomiTeleZoom.planLogical(50f, Float.NaN, false, 100f).mm, E);
        // the RAW viewfinder crops the same as the photo there
        assertEquals(pinned.residual, XiaomiTeleZoom.cropOf(pinned), E);
    }

    @Test
    public void theLogicalCameraThatHoldsTheTeleIsPicked() {
        java.util.Map<String, java.util.Set<String>> ids = new java.util.HashMap<>();
        ids.put("5", new java.util.HashSet<>(java.util.Arrays.asList("3", "2", "4")));
        ids.put("0", new java.util.HashSet<>(java.util.Arrays.asList("3", "2", "4")));
        ids.put("6", new java.util.HashSet<>(java.util.Arrays.asList("2", "3")));
        java.util.Set<String> smooth = new java.util.HashSet<>(java.util.Arrays.asList("0", "5", "6"));
        assertEquals("the lowest id holding the tele", "0", XiaomiTeleZoom.pickLogical("4", ids, smooth));
        assertNull("no logical camera holds camera 7", XiaomiTeleZoom.pickLogical("7", ids, smooth));
        assertNull("without the smooth-transition optics", XiaomiTeleZoom.pickLogical("4", ids, new java.util.HashSet<>()));
        assertEquals("5", XiaomiTeleZoom.pickLogical("4", ids, new java.util.HashSet<>(java.util.Collections.singletonList("5"))));
        assertTrue(XiaomiTeleZoom.compareIds("2", "10") < 0);
        // not this phone (unit test): never routed
        assertNull(XiaomiTeleZoom.logicalRoute(null, "4", null, true));
    }

    @Test
    public void aFailedLogicalSessionStepsDown() {
        assertEquals("stock 0x9002 -> regular", 0, XiaomiTeleZoom.nextOperationMode(XiaomiTeleZoom.STOCK_OPERATION_MODE));
        assertEquals("regular -> the tele alone", -1, XiaomiTeleZoom.nextOperationMode(0));
        assertEquals(36866, XiaomiTeleZoom.STOCK_OPERATION_MODE);
    }

    @Test
    public void theFollowVerdictOnTheLogicalCamera() {
        assertEquals("follows", 1, XiaomiTeleZoom.logicalFollow(100f, 99f, 0));
        assertEquals("pinned for FOLLOW_MS", -1, XiaomiTeleZoom.logicalFollow(100f, 74.4f, XiaomiTeleZoom.FOLLOW_MS));
        assertEquals("pinned, not long enough", 0, XiaomiTeleZoom.logicalFollow(100f, 74.4f, 100));
        assertEquals("at 75 mm nothing tells", 0, XiaomiTeleZoom.logicalFollow(77f, 74.4f, 10_000));
        assertEquals("on the way", 0, XiaomiTeleZoom.logicalFollow(100f, 96f, 10_000));
        assertEquals("no report", 0, XiaomiTeleZoom.logicalFollow(100f, Float.NaN, 10_000));
        assertTrue(XiaomiTeleZoom.clamped(17.2f, 10f));
        assertFalse(XiaomiTeleZoom.clamped(17.2f, 17.2f));
        assertFalse(XiaomiTeleZoom.clamped(4.3f, null));
    }

    /**
     * P41c: the 2x ISZ with the optical zoom on the logical camera, in the dial's ratios: from 6.45x the HAL's dial is half the
     * zoom (the lens 3.225x-4.3x again), the RAW frame in mode 9 covers twice the lens, the photo's crop is what is left.
     */
    @Test
    public void forcedIszHalvesTheDialOnTheLogicalCamera() {
        float mmPerX = XiaomiTeleZoom.MM_PER_USER;
        // 7.0x: ISZ, the lens at 3.5x, no crop once the HAL reports mode 9 with the lens there
        XiaomiTeleZoom.Plan p = XiaomiTeleZoom.planLogical(7f * mmPerX, 3.5f * mmPerX, true, 100f, true);
        org.junit.Assert.assertEquals(3.5f, p.userZoom, 1e-3f);
        org.junit.Assert.assertEquals(3.5f, p.zoomRatio, 1e-3f);
        org.junit.Assert.assertEquals(1f, p.residual, 1e-3f);
        // 12x: the lens at 4.3x in ISZ (8.6x), the rest a crop of the ISZ frame
        p = XiaomiTeleZoom.planLogical(12f * mmPerX, 4.3f * mmPerX, true, 100f, true);
        org.junit.Assert.assertEquals(12f / 8.6f, p.residual, 1e-3f);
        // ISZ from 6.45x (2 x 3.225x), off below 6.41x
        org.junit.Assert.assertTrue(XiaomiTeleZoom.nextIsz(6.46f * mmPerX, false));
        org.junit.Assert.assertFalse(XiaomiTeleZoom.nextIsz(6.4f * mmPerX, false));
        org.junit.Assert.assertFalse(XiaomiTeleZoom.nextIsz(6.39f * mmPerX, true));
        // without the forced ISZ the dial is the zoom (the stock camera's map)
        org.junit.Assert.assertEquals(7f, XiaomiTeleZoom.planLogical(7f * mmPerX, Float.NaN, false, 100f, false).userZoom, 1e-3f);
    }
}
