package com.particlesdevs.photoncamera.circularbarlib.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Which manual parameters are set, and which of them are applied. The owner's rule: with ISO <b>and</b> shutter both
 * manual, exposure compensation is unavailable; a manual EV is kept but not applied (not in the request, not in the
 * summary, not lighting the toggle's dot) and applies again once ISO or shutter returns to auto.
 */
public final class ManualPanelState {
    /** Parameters in the strip's order: ISO, shutter, EV, focus, white balance. */
    public static final int ISO = 0, SHUTTER = 1, EV = 2, FOCUS = 3, WB = 4, COUNT = 5;

    private final boolean[] manual = new boolean[COUNT];

    public ManualPanelState(boolean iso, boolean shutter, boolean ev, boolean focus, boolean wb) {
        manual[ISO] = iso;
        manual[SHUTTER] = shutter;
        manual[EV] = ev;
        manual[FOCUS] = focus;
        manual[WB] = wb;
    }

    /** EV is unavailable while both ISO and shutter are manual. */
    public static boolean evLocked(boolean isoManual, boolean shutterManual) {
        return isoManual && shutterManual;
    }

    public boolean evLocked() {
        return evLocked(manual[ISO], manual[SHUTTER]);
    }

    /** Set by the user (the stored EV counts while locked). */
    public boolean isManual(int param) {
        return manual[param];
    }

    /** Set and in effect: a locked EV is not. */
    public boolean isApplied(int param) {
        return manual[param] && !(param == EV && evLocked());
    }

    /** Whether any applied parameter is manual (the toggle's dot, «Всё на авто», the summary). */
    public boolean anyApplied() {
        for (int i = 0; i < COUNT; i++) if (isApplied(i)) return true;
        return false;
    }

    /** Whether any parameter is set (a locked EV included): what «Всё на авто» resets. */
    public boolean anyManual() {
        for (int i = 0; i < COUNT; i++) if (manual[i]) return true;
        return false;
    }

    /** The applied parameters in the strip's order (the top-bar summary's items). */
    public List<Integer> summary() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) if (isApplied(i)) out.add(i);
        return out;
    }

    /**
     * The EV index sent to the request: the stored one, or 0 while EV is locked (ISO and shutter manual). The stored value
     * is untouched, so it applies again when ISO or shutter returns to auto.
     */
    public static int effectiveEvIndex(int storedEv, boolean isoManual, boolean shutterManual) {
        return evLocked(isoManual, shutterManual) ? 0 : storedEv;
    }
}
