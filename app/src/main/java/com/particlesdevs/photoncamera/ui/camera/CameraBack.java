package com.particlesdevs.photoncamera.ui.camera;

import com.particlesdevs.photoncamera.ui.camera.model.CameraFragmentModel;

/**
 * P319 (user review 2026-10-22, item 8: «Кнопка "назад" все же должна работать как выход из камеры»): what one system Back
 * does on the camera screen (CameraFragment.onBackPressed). Pure, so CameraBackTest checks the order on the JVM.
 * <p>
 * One thing closes per press: a running self-timer first (it is the only one on a clock), then the catalog, the shade's
 * drag / edit mode (a drag only runs in the edit mode), the quick sheet, the LUT tray, the Pro panel (it stays expanded
 * while the sheet hides it, so the sheet goes first). Dialogs and the shade's config menu are windows of their own and take
 * Back before the activity. With nothing open the camera is left: while a shot's frames still arrive the first press only
 * warns; while a shot is captured, queued or merged the task goes to the background (as with Home: the processing
 * foreground service keeps the process, the capture controller stays for the queued shots); else the activity finishes.
 */
final class CameraBack {
    enum Step { TIMER, CATALOG, SHADE_EDIT, SHEET, TRAY, PRO, WARN_CAPTURING, BACKGROUND, LEAVE }

    private CameraBack() {
    }

    /**
     * @param timer       a self-timer counts down
     * @param catalog     the catalog «Добавить в шторку» is open
     * @param shadeEdit   the shade is in its edit mode (or drags a tile)
     * @param sheetLevel  the quick sheet's level ({@link CameraFragmentModel#SHEET_HIDDEN} = closed)
     * @param tray        the LUT tray is open
     * @param pro         the Pro panel is expanded
     * @param warnCapture a shot's frames still arrive and this is the first Back for it
     * @param busy        a shot is captured, queued or merged (ProcessingKeepAlive.busy)
     */
    static Step next(boolean timer, boolean catalog, boolean shadeEdit, int sheetLevel, boolean tray, boolean pro,
                     boolean warnCapture, boolean busy) {
        if (timer) return Step.TIMER;
        if (catalog) return Step.CATALOG;
        if (shadeEdit) return Step.SHADE_EDIT;
        if (sheetLevel != CameraFragmentModel.SHEET_HIDDEN) return Step.SHEET;
        if (tray) return Step.TRAY;
        if (pro) return Step.PRO;
        if (warnCapture) return Step.WARN_CAPTURING;
        return busy ? Step.BACKGROUND : Step.LEAVE;
    }
}
