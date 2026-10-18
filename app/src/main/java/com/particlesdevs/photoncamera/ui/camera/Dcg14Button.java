package com.particlesdevs.photoncamera.ui.camera;

import com.particlesdevs.photoncamera.capture.Dcg14Mode;
import com.particlesdevs.photoncamera.util.Log;

/**
 * P104 / SIMPLE_UI §1: the DCG 14-bit switch - now the «DCG» button of the top bar's pill card (only on modules with a DCG
 * mode, Dcg14Mode.available), the same setting as «Для опытных → 14 бит (DCG)». A tap toggles Dcg14Mode and restarts the
 * camera (its keys are session keys). The old square button in the manual strip's end slot is gone.
 */
final class Dcg14Button {
    private static final String TAG = "Dcg14Button";

    private Dcg14Button() {}

    /** Toggles DCG; returns the message for the band («14 бит (DCG): вкл. · перезапуск») or why it did not switch. */
    static String toggleMode(CameraFragment fragment) {
        int mode = Dcg14Mode.next(Dcg14Mode.mode());
        if (!com.particlesdevs.photoncamera.capture.SensorModeSwitches.setDcg(mode != Dcg14Mode.OFF))
            return com.particlesdevs.photoncamera.util.Lang.t("Нет обычного модуля этой камеры для DCG", "No plain module of this camera for DCG");
        Log.i(TAG, "DCG 14 bit mode " + Dcg14Mode.name(mode));
        if (fragment.captureController != null) fragment.captureController.restartCamera();
        return mode != Dcg14Mode.OFF ? com.particlesdevs.photoncamera.util.Lang.t("14 бит (DCG): вкл. · перезапуск", "14-bit (DCG): on · restart")
                : com.particlesdevs.photoncamera.util.Lang.t("14 бит (DCG): выкл. · перезапуск", "14-bit (DCG): off · restart");
    }
}
