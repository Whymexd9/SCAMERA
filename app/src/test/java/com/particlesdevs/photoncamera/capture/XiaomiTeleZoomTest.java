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
}
