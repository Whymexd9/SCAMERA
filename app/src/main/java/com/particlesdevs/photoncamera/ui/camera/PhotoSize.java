package com.particlesdevs.photoncamera.ui.camera;

import android.hardware.camera2.CameraCharacteristics;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.PixelPro10;
import com.particlesdevs.photoncamera.capture.SensorModeSwitches;
import com.particlesdevs.photoncamera.capture.VivoSdkFullRes;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

/**
 * SIMPLE_UI §1 (owner: «размер фото — переключатель FULLRES, всегда виден в видоискателе»): the photo size of the current module,
 * 12 Мп (the usual output), 50 Мп (FULLRES) and 200 Мп (the X200 Ultra's tele 200 MP), as the top bar's size button and the
 * «Размер фото» sheet show and step them. The switch itself is the one FullResButton had (P108 / P179 / P181): the same
 * SensorModeSwitches call, the same module-profile sync and the same session restart; only its place changed.
 */
public final class PhotoSize {
    private PhotoSize() {}

    public static final int NORMAL = 12, FULL = 50, MAX = 200;

    /** FULLRES on this camera, known from its characteristics (the Pixel) or its sensor-mode profile (vivo / OPPO). */
    private static volatile boolean fullHere;
    private static volatile String boundCamera = "";

    /** Called on every characteristics update (CameraFragment): which sizes this module has. */
    public static void bind(@Nullable CameraCharacteristics characteristics) {
        String id = ScameraApp.getSettings().mCameraID;
        if (characteristics != null) com.particlesdevs.photoncamera.capture.OppoNativeFullRes.learn(id, characteristics); // P192
        boolean device = PixelPro10.device() || com.particlesdevs.photoncamera.capture.VivoQcomModes.device()
                || com.particlesdevs.photoncamera.capture.VivoNativeFullRes.device()
                || com.particlesdevs.photoncamera.capture.OppoNativeFullRes.device();
        fullHere = device && (PixelPro10.fullResAvailable(characteristics) || VivoSdkFullRes.offered(id));
        boundCamera = id == null ? "" : id;
        // P181: the FULLRES profiles of this camera's modules
        com.particlesdevs.photoncamera.settings.FullResProfiles.remember(id, fullHere);
    }

    /** 50 Мп exists on this module. */
    public static boolean fullAvailable() {
        return fullHere;
    }

    /** 200 Мп exists on this module (the X200 Ultra's tele with its two FULLRES sizes). */
    public static boolean maxAvailable() {
        return fullHere && VivoSdkFullRes.teleSizes(boundCamera);
    }

    /** The size in effect: 12, 50 or 200. */
    public static int current() {
        if (!fullHere || !PixelPro10.fullResChosen()) return NORMAL;
        return maxAvailable() && PixelPro10.tele200() ? MAX : FULL;
    }

    /** The next size a tap gives: 12 -> 50 -> (200 where the module has it) -> 12. */
    public static int next(int size, boolean full, boolean max) {
        if (size == NORMAL) return full ? FULL : NORMAL;
        if (size == FULL) return max ? MAX : NORMAL;
        return NORMAL;
    }

    /** «12 Мп» / «50 Мп» / «200 Мп». */
    public static String label(int size) {
        return size + Lang.t(" Мп", " MP");
    }

    /**
     * Switches to {@code size}; returns the message for the band («Размер: 50 Мп · перезапуск») or the reason it did not
     * switch. A real change restarts the camera with the module's profile (as the FULLRES button did).
     */
    public static String set(CameraFragment fragment, int size) {
        if (size != NORMAL && !fullHere) return Lang.t("50 Мп — нет на этом объективе", "50 MP: not on this lens");
        if (size == MAX && !maxAvailable()) return Lang.t("200 Мп — нет на этом объективе", "200 MP: not on this lens");
        if (PixelPro10.fullResSuspended()) // P179: a 200 MP merge runs: the switch waits for it
            return Lang.t("Снимок 200 Мп ещё обрабатывается", "A 200 MP shot is still being processed");
        if (size == current()) return label(size);
        boolean on = size != NORMAL;
        if (on && maxAvailable()) PixelPro10.setTele200(size == MAX);
        if (!SensorModeSwitches.setFullRes(on))
            return Lang.t("Нет обычного модуля этой камеры для полного разрешения", "No plain module of this camera for full resolution");
        Log.i("PhotoSize", "photo size " + size + " MP");
        ModuleRegistry.syncProfile();
        if (fragment != null && fragment.captureController != null) fragment.captureController.restartCamera();
        return Lang.t("Размер: " + label(size) + " · перезапуск", "Size: " + label(size) + " · restart");
    }
}
