package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureRequest;

/** NICE detector controls from the supplied stock Photo request/session log. */
public final class VivoNicePreview {
    private static final CaptureRequest.Key<Integer> MAGIC = new CaptureRequest.Key<>(
            "vivo.control.NiceMagicEnable", Integer.class);
    private static final CaptureRequest.Key<Integer> NICE = new CaptureRequest.Key<>(
            "vivo.capability.capture.nice", Integer.class);
    // com.vivo.stats.aec.so's ReadVivoAECHALParam reads this request field and
    // hdrRunMode(0xc80000)=9 selects the NICE tuning bank (see
    // vivo-aec-scene.h). A live per-scene classifier (setDetectTypeForNICE in
    // libvivo.vaf.system.so) would choose among several modes 0..13 using
    // stock-app-internal UI/session inputs (captureType, uiMode, AI scene
    // score...) that a third-party app cannot supply; that classifier is
    // ported (vivo-nice-scene-selector.h, verified against 10000 native cases)
    // but not wired to a live input source. Per explicit user direction
    // (2026-09-22: a maximally-close port, not a required byte-identical
    // live classifier), this always requests the NICE bracket (mode 9) rather
    // than reproducing that per-scene choice. The exposures/tables that follow
    // remain the vendor's own real-time computation for the real scene; only
    // the choice of *which* HDR bracket to request is fixed, not fabricated.
    private static final CaptureRequest.Key<Integer> SCENE_MODE = new CaptureRequest.Key<>(
            "vivo.control.sceneMode", Integer.class);
    private static final int NICE_SCENE_MODE = 0xc80000;

    private VivoNicePreview() {}

    /** The stock-preview vendor tags are defined by the vivo camera HAL only. */
    public static boolean supported() {
        return "vivo".equalsIgnoreCase(android.os.Build.MANUFACTURER)
                || "iqoo".equalsIgnoreCase(android.os.Build.BRAND);
    }

    // Stock Photo repeating preview (PD2454 VivoCamera, NICE on, rear main), read
    // 2026-09-23 from the stock app's own CaptureRequests. These drive the vendor
    // preview AE (AI AE, motion/night/echo/HDR policy) that the stock NICE solver
    // then reads; without them our preview AE ran a shorter-shutter policy and
    // the solver produced darker, noisier N/L. Omitted on purpose: zoom/crop/track
    // geometry, stream usage, watermark/livephoto/AIGC, colour-effect intensity,
    // environment inputs (lux/temperature/weather) and the per-frame
    // motion-metering array, which the stock app computes itself.
    private static final Object[][] STOCK_PREVIEW = {
        {"vivo.control.relighting", new int[]{0}},
        {"vivo.control.aiSceneType", new int[]{-1, -1}},
        {"vivo.control.zoom_action", new int[]{0}},
        {"vivo.control.filmSize", new int[]{0}},
        {"vivo.control.singleBlur_level", new int[]{0}},
        {"vivo.control.currentModeEx", new long[]{0}},
        {"vivo.control.is_rawnr_mode", new int[]{0}},
        {"vivo.control.face.enlandmark", new int[]{-1}},
        {"vivo.control.supermoon_preview_mode_for_base", new int[]{3}},
        {"vivo.control.isdecreaseExposure", new int[]{0}},
        {"vivo.control.motion_level", new int[]{0}},
        {"vivo.control.lot.state", new int[]{0}},
        {"vivo.control.sod.state", new int[]{0}},
        {"vivo.control.humanbodyTrack", new int[]{0}},
        {"vivo.control.previewdetect", new long[]{502831250416135L}},
        {"vivo.control.videoalgotype", new int[]{0}},
        {"vivo.control.isCapture", new int[]{0}},
        {"vivo.control.videoNight", new int[]{-1}},
        {"vivo.control.motionVersion", new int[]{119}},
        {"vivo.control.horizon_type", new int[]{-1}},
        {"vivo.control.lotAlgo.enable", new int[]{1}},
        {"vivo.control.zeiss_ps", new int[]{0}},
        {"vivo.control.captureStateForDetect", new int[]{0}},
        {"vivo.control.lastFallback", new int[]{1}},
        {"vivo.control.aiae.enable", new int[]{1}},
        {"vivo.control.ic.scene", new int[]{0}},
        {"vivo.control.twoPassNRDetection", new int[]{0}},
        {"vivo.control.twoPassNRSupport", new int[]{1}},
        {"vivo.control.SportStagger", new int[]{0}},
        {"vivo.control.isCloseStagger", new byte[]{0}},
        {"vivo.control.disableHDR", new byte[]{0}},
        {"vivo.control.enableAutoFallback", new int[]{1}},
        {"vivo.control.echo.mode", new int[]{2}},
        {"vivo.control.isLowLightOptimization", new byte[]{0}},
        {"vivo.control.MultiSportStagger", new int[]{1, 3}},
        {"vivo.control.quickNightCapture", new int[]{1}},
        {"vivo.control.ic.effect", new int[]{0}},
        {"vivo.control.preview.hdr.state", new int[]{2}},
        {"vivo.control.multi.object.track", new int[]{6}},
        {"vivo.control.session.uiSwitch_state", new int[]{0}},
        {"vivo.control.isShortTorchFlashMode", new int[]{0}},
        {"vivo.control.camera_sense_on", new byte[]{0}},
        {"vivo.parameter.enableMacroFallback", new int[]{0}},
        {"vivo.parameter.enableMacroDetect", new int[]{1}},
        {"vivo.capability.capture.night", new int[]{1}},
        {"vivo.capability.capture.superNight", new int[]{0}},
        {"vivo.capability.capture.fastSuperns", new int[]{1}},
        {"vivo.capability.capture.hdr", new int[]{1}},
        {"vivo.capability.capture.lowLight", new int[]{0}},
        {"vivo.capability.capture.document", new int[]{0}},
        {"vivo.capability.capture.rawHdr", new int[]{1}},
        {"vivo.capability.capture.tripod", new int[]{1}},
        {"vivo.capability.capture.starHandheld", new int[]{0}},
        {"vivo.capability.capture.remosic", new int[]{0}},
        {"vivo.capability.capture.portraitNight", new int[]{0}},
        {"vivo.capability.capture.pixelShift", new int[]{0}},
        {"vivo.capability.capture.motion", new int[]{1}},
        {"vivo.capability.capture.llhdr", new int[]{1}},
        {"vivo.capability.capture.hdrlite", new int[]{1}},
        {"vivo.capability.capture.showmode", new int[]{0}},
        {"vivo.capability.capture.hd", new int[]{1}},
        {"vivo.capability.capture.extremeNight", new int[]{0}},
        {"vivo.capability.capture.clearZoom", new int[]{0}},
        {"vivo.capability.capture.aiNr", new int[]{1}},
        {"vivo.capability.capture.aiScene", new int[]{1}},
        {"vivo.capability.capture.allinFocus", new int[]{0}},
        {"vivo.capability.capture.docFocus", new int[]{1}},
        {"vivo.capability.capture.nightRemosaic", new int[]{1}},
        {"vivo.capability.detect.videoNight", new int[]{0}},
        {"vivo.capability.detect.sceneChange", new int[]{0}},
        {"vivo.capability.detect.palm", new int[]{0}},
        {"vivo.capability.detect.child", new int[]{0}},
        {"vivo.capability.detect.banding", new int[]{1}},
        {"vivo.capability.detect.dirty", new int[]{0}},
        {"vivo.capability.detect.awb", new int[]{1}},
        {"vivo.capability.detect.hdr_highlight", new int[]{1}},
    };

    // Stock preview motion metering (409 floats) for a still scene: header 3,
    // level, 3; 12x12 block motion at [3..146] (zero when nothing moves);
    // block count 144 at [403]; int 1 at [407]. The stock app fills the level
    // and blocks from its own preview motion detector.
    private static float[] stillMotionMetering() {
        float[] m = new float[409];
        m[0] = 3f;
        m[2] = 3f;
        m[403] = 144f;
        m[407] = Float.intBitsToFloat(1);
        return m;
    }

    /** Stock preview AE frame-rate range for Photo. */
    public static final android.util.Range<Integer> STOCK_FPS = new android.util.Range<>(5, 30);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int applyStockProfile(CaptureRequest.Builder builder) {
        int applied = 0;
        for (Object[] entry : STOCK_PREVIEW) {
            try {
                CaptureRequest.Key key = new CaptureRequest.Key((String) entry[0], entry[1].getClass());
                builder.set(key, entry[1]);
                applied++;
            } catch (RuntimeException unsupported) {
                android.util.Log.w("NICE_CAPTURE", "stock preview tag unavailable: " + entry[0]);
            }
        }
        // Stock "1x" on the main camera reports zoom_ratio 1.5 to the vendor AE
        // (vendor tag only; the Camera2 crop zoom stays untouched here).
        try {
            builder.set(new CaptureRequest.Key<>("vivo.control.zoom_ratio", float[].class), new float[]{1.5f});
            applied++;
        } catch (RuntimeException unsupported) {
            android.util.Log.w("NICE_CAPTURE", "stock preview tag unavailable: vivo.control.zoom_ratio");
        }
        try {
            builder.set(new CaptureRequest.Key<>("vivo.parameter.VivoRawHdrMotionMetering", float[].class),
                    stillMotionMetering());
            applied++;
        } catch (RuntimeException unsupported) {
            android.util.Log.w("NICE_CAPTURE", "stock preview tag unavailable: VivoRawHdrMotionMetering");
        }
        // Stock: AE antibanding AUTO, scene-mode control with FACE_PRIORITY.
        builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
        builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY);
        return applied;
    }

    public static void applySession(CaptureRequest.Builder builder) {
        // Stock places this in the session's global parameters as well as preview.
        builder.get(MAGIC); // Resolve the vendor key before changing the builder.
        builder.set(MAGIC, 1);
        applyPreviewEis(builder);
    }

    /**
     * The stock Photo mode's preview EIS (vivo camera app log 2026-09-20, X200 Ultra: «setSessionParameter, key:
     * vivo.control.eis.config.enable, value: 5», also in every request's global parameters). Without it the preview had OIS
     * only, and the X300 Ultra's preview lost its stabilisation after a shot (P38; the 2026-10-08 trace shows OIS on before and
     * after and no EIS key in our requests). A HAL without the tag skips it; nice_dev.txt "vivo_preview_eis 0" turns it off.
     */
    static final String EIS_CONFIG = "vivo.control.eis.config.enable";
    static final int STOCK_PHOTO_EIS = 5;

    static boolean applyPreviewEis(CaptureRequest.Builder builder) {
        if (!com.particlesdevs.photoncamera.settings.PreferenceKeys.niceDevSwitch("vivo_preview_eis", true)) return false;
        try {
            builder.set(new CaptureRequest.Key<>(EIS_CONFIG, Integer.class), STOCK_PHOTO_EIS);
            return true;
        } catch (RuntimeException unsupported) {
            return false;
        }
    }

    public static void applyRepeating(CaptureRequest.Builder builder) {
        // Resolve all keys first. An absent key must not leave half a profile.
        Integer magic = builder.get(MAGIC);
        Integer nice = builder.get(NICE);
        Integer sceneMode = builder.get(SCENE_MODE);
        try {
            builder.set(MAGIC, 1);
            builder.set(NICE, 1); // Stock AUTO; this is not an AE exposure array.
            // Held for the whole repeating preview, not a single-shot switch:
            // the native AEC's per-request history (see
            // docs/nice-runtime-ratio-source-20260922.md) only produces a
            // non-degenerate short/extra-short plan once it has several
            // consecutive real frames under this scene mode.
            builder.set(SCENE_MODE, NICE_SCENE_MODE);
            android.util.Log.i("NICE_CAPTURE", "stock preview profile tags=" + applyStockProfile(builder)
                    + "/" + STOCK_PREVIEW.length + " eis=" + applyPreviewEis(builder));
        } catch (RuntimeException failure) {
            try {
                builder.set(MAGIC, magic);
                builder.set(NICE, nice);
                builder.set(SCENE_MODE, sceneMode);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }
}
