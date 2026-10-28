package com.particlesdevs.photoncamera.settings;

import android.os.Build;

import com.particlesdevs.photoncamera.util.Lang;

import java.util.Map;

/**
 * {@link SettingsAvailability} with the facts of this phone: the 10-bit HEIC encoder, the vivo stock AE observer, the
 * colour matrix that replaces «Цветовой метод» and the Xiaomi 17 Ultra's smooth zoom. They need
 * Android, and the host check (tools/check_settings_model.py) compiles SettingsAvailability on its own, so the settings
 * screen and the shade build it here.
 */
public final class DeviceAvailability {
    private DeviceAvailability() {}

    public static SettingsAvailability of(Map<String, ?> values) {
        return new SettingsAvailability(values)
                .heic10Unavailable(com.particlesdevs.photoncamera.processing.heif.Heic10Support.unavailableReason())
                .hdrUnavailable(com.particlesdevs.photoncamera.processing.color.HdrOutput.unavailableReason())
                .colorMethodOverride(colorMethodOverride())
                .xiaomiSmoothZoom(com.particlesdevs.photoncamera.capture.XiaomiTeleZoom.phone())
                .fullResHwRemosaic(com.particlesdevs.photoncamera.capture.VivoSdkFullRes.hardwareRemosaicOffered())
                .fullResSizes(FullResProfiles.sizeOffered("50"), FullResProfiles.sizeOffered("200"))
                .oppoTunedColor(com.particlesdevs.photoncamera.processing.render.OppoTunedColor.applies())
                .mosaicStreams(com.particlesdevs.photoncamera.processing.MosaicBlockStore.anyMosaic())
                .bentoBlend(bentoBlend())
                .dcg(dcgOn());
    }

    /**
     * P320: the camera running now takes DCG frames (its session asked for them: the «DCG 14 бит» switch on a camera with the mode,
     * or the Pixel 10 Pro's main); another camera of the phone keeps its shadow-frame rows.
     */
    static boolean dcgOn() {
        try {
            return com.particlesdevs.photoncamera.processing.RawLevels.dcgFrames();
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /** The highlight frames are blended after the merge (P310; scam_dev.txt "hybrid_bento_blend 0" mixes them inside it). */
    static boolean bentoBlend() {
        try {
            return PreferenceKeys.hybridSwitch("bento_blend", true);
        } catch (RuntimeException unknown) {
            return true;
        }
    }

    /**
     * Why «Цветовой метод» cannot act on this phone, or null (the row stays enabled). Parameters picks the colour transform from the
     * choice; the OPPO Find X7 Ultra's tuned matrix (per colour temperature) replaces it on every shot, and a camera whose DNG forward
     * matrices are the sRGB -> XYZ placeholder never renders through them (Parameters' A3 route: the ISP matrix, the plain-session
     * table or the colour matrices alone, whatever the choice). P370 (review): only those disable the row - where the capture
     * result's ISP matrix renders the 12 MP photos (vivo, a preview transform shaped as a colour matrix) the choice still acts on
     * mosaic shots with the app's own white balance and on «Захват» with the ISP branch off, so the row stays enabled (a saved
     * 0 or 2 can be changed back) and its summary says what it acts on.
     */
    static String colorMethodOverride() {
        if (com.particlesdevs.photoncamera.processing.render.OppoTunedColor.applies())
            return Lang.t("На этом OPPO цвет задаёт настроенная матрица OPPO по цветовой температуре: выбор не действует.",
                    "On this OPPO the tuned OPPO matrix for the colour temperature sets the colour: the choice has no effect.");
        if (placeholderForward())
            return Lang.t("Матрицы DNG этой камеры — заглушка sRGB: цвет задаёт матрица ISP или таблица обычной сессии, выбор не действует.",
                    "This camera's DNG forward matrices are an sRGB placeholder: the ISP matrix or the plain-session table sets the colour; the choice has no effect.");
        return null;
    }

    /** P370 (A6): the running camera's DNG forward matrices are both the sRGB -> XYZ placeholder. */
    static boolean placeholderForward() {
        try {
            final android.hardware.camera2.CameraCharacteristics c = com.particlesdevs.photoncamera.capture.CaptureController.mCameraCharacteristics;
            if (c == null) return false;
            return placeholder(c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_FORWARD_MATRIX1))
                    && placeholder(c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_FORWARD_MATRIX2));
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    private static boolean placeholder(android.hardware.camera2.params.ColorSpaceTransform m) {
        if (m == null) return false;
        final float[] f = new float[9];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) f[r * 3 + c] = m.getElement(c, r).floatValue();
        return com.particlesdevs.photoncamera.processing.render.OppoTunedColor.placeholderForward(f);
    }
}
