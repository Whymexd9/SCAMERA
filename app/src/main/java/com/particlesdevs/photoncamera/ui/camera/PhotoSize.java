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
 * SensorModeSwitches call, the same module-profile sync and the same session restart; only its place changed (the
 * FullResButton overlay above the manual strip is gone).
 * <p>
 * P214h (speed-ux C2; P210: «all own rows fail, the size goes off, the button hides»), carried over from the FULLRES button: a
 * size whose sensor-mode rows all failed stays in the sheet, dimmed; a tap on it shows what the trials remember
 * (FullResTrials.status: the rows, why each failed, the camera's own remosaic row) with «Попробовать снова», which forgets the
 * camera's trials and turns that size on. While a FULLRES merge runs the switch names the size being merged.
 */
public final class PhotoSize {
    private PhotoSize() {}

    public static final int NORMAL = 12, FULL = 50, MAX = 200;

    /** FULLRES on this camera, known from its characteristics (the Pixel) or its sensor-mode profile (vivo / OPPO). */
    private static volatile boolean fullHere;
    private static volatile String boundCamera = "";
    /** P214h: FULLRES of this camera is off by its trials (every row failed): its sizes stay in the sheet, dimmed. */
    private static volatile boolean fullDead;

    /** Called on every characteristics update (CameraFragment): which sizes this module has. */
    public static void bind(@Nullable CameraCharacteristics characteristics) {
        String id = ScameraApp.getSettings().mCameraID;
        if (characteristics != null) com.particlesdevs.photoncamera.capture.OppoNativeFullRes.learn(id, characteristics); // P192
        boolean device = PixelPro10.device() || com.particlesdevs.photoncamera.capture.VivoQcomModes.device()
                || com.particlesdevs.photoncamera.capture.VivoNativeFullRes.device()
                || com.particlesdevs.photoncamera.capture.OppoNativeFullRes.device();
        fullHere = device && (PixelPro10.fullResAvailable(characteristics) || VivoSdkFullRes.offered(id));
        // P214h: a camera whose rows all failed keeps its sizes, dimmed - a tap says why and offers «Попробовать снова»
        fullDead = device && !fullHere && VivoSdkFullRes.exhausted(id);
        boundCamera = id == null ? "" : id;
        String trials = com.particlesdevs.photoncamera.capture.FullResTrials.dump(id);
        if (!trials.isEmpty()) Log.i("PhotoSize", "camera " + id + " FULLRES " + (fullDead ? "off by its trials: " : "trials: ") + trials);
        // P181: the FULLRES profiles of this camera's modules
        com.particlesdevs.photoncamera.settings.FullResProfiles.remember(id, fullHere);
    }

    /** Tests: the sizes of the bound module without its characteristics. */
    static void availableForTest(boolean full, String camera) {
        fullHere = full;
        fullDead = false;
        boundCamera = camera == null ? "" : camera;
    }

    /**
     * P214h: {@code size} (50 or 200 MP) is off by the camera's trials - every row of that size (the tele's two sizes) or of the
     * camera failed. The sheet shows it dimmed; a tap explains.
     */
    public static boolean dead(int size) {
        if (size == NORMAL) return false;
        if (fullHere && VivoSdkFullRes.teleSizes(boundCamera)) return VivoSdkFullRes.sizeExhausted(boundCamera, size == MAX);
        return fullDead && size == FULL;
    }

    /**
     * P214h: of the tele's two sizes the one that runs while FULLRES is on - the chosen size, or the other when the chosen one
     * has no row left (the camera takes its other size then), none when both are off. True: 200 MP.
     */
    static boolean lights200(boolean chosen200, boolean dead50, boolean dead200) {
        if (dead200) return false;
        return dead50 || chosen200;
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
        if (!maxAvailable()) return FULL;
        // P214h: the size that runs - the chosen one, or the other when the chosen one has no row left
        return lights200(PixelPro10.tele200(), dead(FULL), dead(MAX)) ? MAX : dead(FULL) && dead(MAX) ? NORMAL : FULL;
    }

    /** The next size a tap gives: 12 -> 50 -> (200 where the module has it) -> 12. */
    public static int next(int size, boolean full, boolean max) {
        if (size == NORMAL) return full ? FULL : NORMAL;
        if (size == FULL) return max ? MAX : NORMAL;
        return NORMAL;
    }

    /**
     * P214h (speed-ux C2): why the camera's FULLRES or this size is off - the rows tried and why each failed, the camera's own
     * remosaic row and «FULLRES: аппаратный ремозаик» (FullResTrials.status) - with «Попробовать снова», which forgets the
     * camera's trials and turns that size on (the camera restarts at its first row).
     */
    public static void explain(CameraFragment fragment, int size) {
        final android.content.Context c = fragment == null ? null : fragment.getContext();
        if (c == null) return;
        final String camera = boundCamera;
        String status = com.particlesdevs.photoncamera.capture.FullResTrials.status(camera);
        if (status == null) status = Lang.t(c, "FULLRES недоступен на этой камере", "FULLRES is not available on this camera");
        Log.i("PhotoSize", "camera " + camera + " FULLRES trials shown (" + size + " MP off)");
        com.particlesdevs.photoncamera.ui.settings.DialogStyle.builder(c)
                .setTitle(Lang.t(c, label(size) + " выключен", label(size) + " is off"))
                .setMessage(status + "\n\n" + Lang.t(c,
                        "«Попробовать снова» забывает эти пробы: режимы этой камеры проверяются заново с первого.",
                        "“Try again” forgets these trials: this camera's modes are tried again from the first."))
                .setNegativeButton(Lang.t(c, "Закрыть", "Close"), null)
                .setPositiveButton(Lang.t(c, "Попробовать снова", "Try again"), (d, w) -> retry(fragment, size))
                .show();
    }

    /** P214h: «Попробовать снова»: the trials go and the size turns on (its first row). */
    private static void retry(CameraFragment fragment, int size) {
        com.particlesdevs.photoncamera.capture.FullResTrials.retry(boundCamera);
        fullDead = false;
        fullHere = fullHere || VivoSdkFullRes.offered(boundCamera);
        String message = set(fragment, size);
        if (message != null) fragment.showSnackBar(message);
        fragment.refreshTopBar();
    }

    /** «12 Мп» / «50 Мп» / «200 Мп». */
    public static String label(int size) {
        return size + Lang.t(" Мп", " MP");
    }

    /** P319: the stream the open session captures (its RAW reader, or the YUV / JPEG target without RAW) and its camera. */
    private static volatile int streamWidth, streamHeight;
    private static volatile String streamCamera = "";

    /** The normal (non-FULLRES) stream last seen per camera: the «Обычный» row names its size while FULLRES runs. */
    private static final java.util.Map<String, int[]> normalStreams = new java.util.concurrent.ConcurrentHashMap<>();

    public static void stream(@Nullable String camera, int width, int height) {
        streamCamera = camera == null ? "" : camera;
        streamWidth = width;
        streamHeight = height;
        if (current() == NORMAL && width > 0 && height > 0) normalStreams.put(streamCamera, new int[]{width, height});
    }

    /** P319: the «Обычный» size of the bound camera in the gallery's words («12,6 Мп», «8 Мп»); «12 Мп» before its first session. */
    public static String normalBadge() {
        return normalBadge(boundCamera);
    }

    /** P319: the camera the sizes are bound to (the open one). */
    public static String boundCamera() {
        return boundCamera;
    }

    /** P319: the «Обычный» size of {@code camera} as last seen in a session of it; «12 Мп» before one. */
    public static String normalBadge(@Nullable String camera) {
        final int[] s = camera == null ? null : normalStreams.get(camera);
        if (s == null) return label(NORMAL);
        final int[] out = output(s[0], s[1]);
        final String text = com.particlesdevs.photoncamera.gallery.ui.PhotoInfo.megapixels(out[0], out[1]);
        return text != null ? text : label(NORMAL);
    }

    /**
     * P319 (user report 2026-10-22: «в верхнем правом углу надпись "12 Мп" даже, если камера имеет большее или меньшее
     * разрешение (не забываем про фронталки)»): the pill names the photo this lens really writes - the session's stream, its
     * «16:9» crop and, for an SHDR shot, «Разрешение» - in the gallery's words («12,6 Мп», «8 Мп», «50 Мп»); the size class
     * (12 / 50 / 200) only until the session has its stream.
     */
    public static String badge() {
        final int w = streamWidth, h = streamHeight;
        if (w <= 0 || h <= 0) return label(current());
        final int[] out = output(w, h);
        final String text = com.particlesdevs.photoncamera.gallery.ui.PhotoInfo.megapixels(out[0], out[1]);
        return text != null ? text : label(current());
    }

    private static int[] output(int w, int h) {
        try {
            final boolean hybrid = com.particlesdevs.photoncamera.settings.PreferenceKeys.isHybridShot();
            return output(w, h, ScameraApp.getSettings().aspect169,
                    hybrid ? com.particlesdevs.photoncamera.settings.PreferenceKeys.hybridOutputMode() : "sensor");
        } catch (RuntimeException e) {
            Log.d("PhotoSize", "output size: " + e);
            return new int[]{w, h};
        }
    }

    /** The photo of a {@code w} x {@code h} stream: the «16:9» crop of a landscape frame, then «Разрешение» ({@code mode}). */
    static int[] output(int w, int h, boolean wide169, String mode) {
        int ow = w, oh = h;
        if (wide169 && ow > oh) oh = com.particlesdevs.photoncamera.processing.Wide169.rows(ow, oh);
        if ("2x".equals(mode)) return new int[]{2 * ow, 2 * oh};
        if ("12".equals(mode) || "16".equals(mode) || "20".equals(mode)) {
            android.graphics.Point t = com.particlesdevs.photoncamera.settings.PreferenceKeys.hybridTargetSize(Integer.parseInt(mode), ow, oh);
            return new int[]{t.x, t.y};
        }
        return new int[]{ow, oh};
    }


    /**
     * Switches to {@code size}; returns the message for the band («Размер: 50 Мп · перезапуск») or the reason it did not
     * switch. A real change restarts the camera with the module's profile (as the FULLRES button did).
     */
    public static String set(CameraFragment fragment, int size) {
        if (dead(size)) { // P214h: a size off by its trials says why instead
            explain(fragment, size);
            return null;
        }
        if (size != NORMAL && !fullHere) return Lang.t("50 Мп — нет на этом объективе", "50 MP: not on this lens");
        if (size == MAX && !maxAvailable()) return Lang.t("200 Мп — нет на этом объективе", "200 MP: not on this lens");
        if (PixelPro10.fullResSuspended()) // P179: a FULLRES merge runs: the switch waits for it (P214h: names its size)
            return PixelPro10.suspendedText();
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
