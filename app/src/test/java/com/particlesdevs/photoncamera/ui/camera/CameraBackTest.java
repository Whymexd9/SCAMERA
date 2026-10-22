package com.particlesdevs.photoncamera.ui.camera;

import static com.particlesdevs.photoncamera.ui.camera.model.CameraFragmentModel.SHEET_FULL;
import static com.particlesdevs.photoncamera.ui.camera.model.CameraFragmentModel.SHEET_HIDDEN;
import static com.particlesdevs.photoncamera.ui.camera.model.CameraFragmentModel.SHEET_PEEK;
import static org.junit.Assert.assertEquals;

import com.particlesdevs.photoncamera.ui.camera.CameraBack.Step;

import org.junit.Test;

/**
 * P319 (user review item 8): Back closes one open thing per press and, with nothing open, leaves the camera - it never does
 * nothing (before P319 the camera screen swallowed Back at HIDDEN since 48554ac9).
 */
public class CameraBackTest {
    private static Step next(boolean timer, boolean catalog, boolean edit, int sheet, boolean tray, boolean pro,
                             boolean warn, boolean busy) {
        return CameraBack.next(timer, catalog, edit, sheet, tray, pro, warn, busy);
    }

    @Test
    public void nothingOpenLeavesTheCamera() {
        assertEquals(Step.LEAVE, next(false, false, false, SHEET_HIDDEN, false, false, false, false));
    }

    @Test
    public void aShotInProcessingSendsTheTaskToTheBackgroundInsteadOfFinishing() {
        assertEquals(Step.BACKGROUND, next(false, false, false, SHEET_HIDDEN, false, false, false, true));
    }

    @Test
    public void theFirstBackDuringACaptureOnlyWarnsTheSecondLeaves() {
        assertEquals(Step.WARN_CAPTURING, next(false, false, false, SHEET_HIDDEN, false, false, true, true));
        // the fragment passes warn = false within BACK_AGAIN_MS of the warning; busy is still true then
        assertEquals(Step.BACKGROUND, next(false, false, false, SHEET_HIDDEN, false, false, false, true));
    }

    @Test
    public void eachOpenThingClosesAlone() {
        assertEquals(Step.TIMER, next(true, false, false, SHEET_HIDDEN, false, false, false, false));
        assertEquals(Step.CATALOG, next(false, true, false, SHEET_HIDDEN, false, false, false, false));
        assertEquals(Step.SHADE_EDIT, next(false, false, true, SHEET_PEEK, false, false, false, false));
        assertEquals(Step.SHEET, next(false, false, false, SHEET_PEEK, false, false, false, false));
        assertEquals(Step.SHEET, next(false, false, false, SHEET_FULL, false, false, false, false));
        assertEquals(Step.TRAY, next(false, false, false, SHEET_HIDDEN, true, false, false, false));
        assertEquals(Step.PRO, next(false, false, false, SHEET_HIDDEN, false, true, false, false));
    }

    @Test
    public void theOrderIsTimerCatalogEditSheetTrayProThenLeave() {
        // everything at once: one press per layer, outermost first
        assertEquals(Step.TIMER, next(true, true, true, SHEET_PEEK, true, true, true, true));
        assertEquals(Step.CATALOG, next(false, true, true, SHEET_PEEK, true, true, true, true));
        assertEquals(Step.SHADE_EDIT, next(false, false, true, SHEET_PEEK, true, true, true, true));
        // the Pro panel stays expanded under an open sheet (it is only hidden): the sheet closes first
        assertEquals(Step.SHEET, next(false, false, false, SHEET_PEEK, false, true, false, false));
        assertEquals(Step.TRAY, next(false, false, false, SHEET_HIDDEN, true, true, true, true));
        assertEquals(Step.PRO, next(false, false, false, SHEET_HIDDEN, false, true, true, true));
        // an open layer is closed even while a shot is processed: no leaving under it
        assertEquals(Step.PRO, next(false, false, false, SHEET_HIDDEN, false, true, false, true));
    }
}
