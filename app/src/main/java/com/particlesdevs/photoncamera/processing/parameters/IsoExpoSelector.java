package com.particlesdevs.photoncamera.processing.parameters;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.TonemapCurve;
import com.particlesdevs.photoncamera.util.Log;
import android.util.Range;
import android.util.SizeF;

import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import java.util.ArrayList;
import java.util.Locale;

public class IsoExpoSelector {
    public static final int baseFrame = 1;
    private static final String TAG = "IsoExpoSelector";
    public static boolean HDR = false;
    public static boolean useTripod = false;
    public static final int patternSize = 3;
    public static ArrayList<ExpoPair> pairs = new ArrayList<>();
    public static ArrayList<ExpoPair> fullpairs = new ArrayList<>();
    public static long lastSelectedExposure = 0;
    private static ExpoPair hdrPlusBasePair;

    /**
     * Request a sensor-linear source. RAW itself is not tone-mapped, however
     * setting every available ISP control explicitly also keeps auxiliary
     * streams and vendor pipelines from silently sharpening, denoising or
     * applying a display curve before their metadata reaches the RAW merge.
     */
    public static void applyLinearCapture(CaptureRequest.Builder builder) {
        try {
            builder.set(CaptureRequest.TONEMAP_MODE,
                    CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
            float[] identity = new float[]{0.0f, 0.0f, 1.0f, 1.0f};
            builder.set(CaptureRequest.TONEMAP_CURVE,
                    new TonemapCurve(identity, identity, identity));
        } catch (IllegalArgumentException ignored) {
            Log.w(TAG, "Linear contrast curve is not exposed by this camera");
        }
        try {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
        } catch (IllegalArgumentException ignored) {
            Log.w(TAG, "EDGE_MODE_OFF is not exposed by this camera");
        }
        try {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_OFF);
        } catch (IllegalArgumentException ignored) {
            Log.w(TAG, "NOISE_REDUCTION_MODE_OFF is not exposed by this camera");
        }
    }

    // ---- Shutter-Priority / Dynamic Low-Light AE Curve ----
    // Instead of letting stock 3A pick a fast shutter + high ISO, we keep the SAME
    // total exposure the platform metered (exposure_time * iso is still a valid
    // brightness target) and re-split it: push shutter time up first - more real
    // photons land on the sensor per frame, which is a genuine shot-noise SNR win
    // even at identical brightness - and only fall back to ISO once a per-frame
    // time cap is hit. That cap is not one fixed number: it slides between a
    // "start extending here" value and a darker-scene "ceiling" as metered scene
    // darkness increases (see ExpoPair#applyShutterPriorityCurve), so behavior
    // changes smoothly with light level instead of jumping between presets.
    //
    // These are tuned starting points, not measured hardware limits - adjust to taste.
    private static final int MIN_ISO_NORMALIZED = 100; // floor we always try first (ISO-100 basis)
    private static final double CAP_RAMP_STOPS = 4.0;  // stops of extra darkness to slide *_START -> *_END
    private static final double CLEAN_ISO_STEP_FACTOR = 2.0; // hardware analog gain stages are conventionally doublings of the base ISO

    private static final long PHOTO_HANDHELD_CAP_START = ExposureIndex.sec / 30; // 1/30s
    private static final long PHOTO_HANDHELD_CAP_END   = ExposureIndex.sec / 15; // 1/15s

    private static final long MOTION_HANDHELD_CAP_START = ExposureIndex.sec / 250; // 1/250s
    private static final long MOTION_HANDHELD_CAP_END   = ExposureIndex.sec / 125; // 1/125s

    private static final long NIGHT_HANDHELD_CAP_START = ExposureIndex.sec / 8;  // 1/8s
    private static final long NIGHT_HANDHELD_CAP_END   = ExposureIndex.sec / 3;  // 1/3s

    private static final long TRIPOD_CAP_START = ExposureIndex.sec / 4;          // 1/4s
    private static final long TRIPOD_CAP_END   = ExposureIndex.sec * 2;          // 2s

    public static void setExpo(CaptureRequest.Builder builder, int step, CaptureController captureController) {
        applyLinearCapture(builder);
        Log.v(TAG, "InputParams: " +
                "expo time:" + ExposureIndex.sec2string(ExposureIndex.time2sec(captureController.mPreviewExposureTime)) +
                " iso:" + captureController.mPreviewIso+ " analog:"+getISOAnalog());
        if(step == 0) fullpairs.clear();
        ExpoPair pair = GenerateExpoPair(step,captureController);
        fullpairs.add(pair);
        Log.v(TAG, "IsoSelected:" + pair.iso +
                " ExpoSelected:" + ExposureIndex.sec2string(ExposureIndex.time2sec(pair.exposure)) + " sec step:" + step + " HDR:" + HDR + " total exposure:" + ExposureIndex.time2sec(pair.exposure)*pair.iso);

        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, pair.exposure);
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, (int)pair.iso);
        lastSelectedExposure = pair.exposure;
    }

    /** Capture the denoising portion of the HDR+ burst at strictly constant exposure. */
    public static void setHdrPlusExpo(CaptureRequest.Builder builder, int step,
                                      CaptureController captureController) {
        applyLinearCapture(builder);
        if (step == 0 || hdrPlusBasePair == null) {
            fullpairs.clear();
            hdrPlusBasePair = GenerateExpoPair(-1, captureController);
        }
        ExpoPair pair = new ExpoPair(hdrPlusBasePair);
        pair.curlayer = ExpoPair.exposureLayer.Normal;
        pair.isHighlightFrame = false;
        pair.isLongFrame = false;
        pair.layerMpy = 1.0f;
        fullpairs.add(pair);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, pair.exposure);
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, pair.iso);
        lastSelectedExposure = pair.exposure;
    }

    /**
     * Appends one deliberately ultra-short RAW exposure to the burst.  It is not
     * part of the constant-exposure denoising stack: downstream it becomes the
     * low-exposure reference and the regular frames are radiometrically scaled
     * to it.  Keeping ISO unchanged preserves the sensor noise model while the
     * shorter shutter provides the user-selected amount of highlight headroom.
     */
    public static boolean setUltraShortExpo(CaptureRequest.Builder builder,
                                            CaptureController captureController) {
        applyLinearCapture(builder);
        ExpoPair source = hdrPlusBasePair != null
                ? hdrPlusBasePair : GenerateExpoPair(-1, captureController);
        ExpoPair pair = new ExpoPair(source);
        int requestedEv = Math.max(1, Math.min(8, PreferenceKeys.getShortExposureEvValue()));
        double factor = Math.scalb(1.0, requestedEv);
        // Clamp the short frame so the whole burst stays inside the HDR ratio ceiling.
        // GCam computes a short and a long AE independently and then pulls the short one
        // up until their ratio fits max_hdr_ratio_default; the shot dump shows an ideal
        // ratio of 17.48 being reduced to 9.80 exactly this way. The long frame is already
        // committed at this point, so the short frame is what gives.
        float maxRatio = PreferenceKeys.getMaxHdrRatio();
        if (maxRatio > 1.0f) {
            double longFactor = Math.scalb(1.0,
                    Math.max(0, Math.min(8, PreferenceKeys.getLongExposureEvValue())));
            double spread = factor * longFactor;
            if (spread > maxRatio) {
                // Pull both ends in by the same proportion instead of making the short
                // frame absorb the whole overshoot: with a long frame at +4 EV the short
                // one was clamped all the way to 1.0 and stopped being a highlight donor
                // at all. Splitting the correction keeps both frames useful.
                double excess = Math.sqrt(spread / maxRatio);
                double clamped = Math.max(1.0, factor / excess);
                Log.i(TAG, "HDR ratio " + spread + " exceeds the ceiling " + maxRatio
                        + ", short frame factor " + factor + " -> " + clamped
                        + " (long frame factor " + longFactor + " -> "
                        + Math.max(1.0, longFactor / excess) + ")");
                factor = clamped;
            }
        }
        // Drop the frame when the ceiling has collapsed the spread to nothing. At a
        // factor of 1 this frame is the base frame: same shutter, same ISO, no highlight
        // headroom. It is not a bracket member at that point, just an extra exposure
        // occupying a slot and lengthening the burst - and a longer burst means more
        // hand-shake and more to align. GCam gates the ultra-short frame on the HDR
        // ratio as well: in the dump the burst is built while Final HDR ratio is 7.75,
        // and fraction_pixels_clipped_at_final_short_tet reads 0.0000 there because it
        // reports the clipping left *after* the short TET was chosen. That number is the
        // outcome of the bracket, not the trigger for it.
        if (factor < ULTRA_SHORT_MIN_FACTOR) {
            Log.i(TAG, "Skipping the ultra-short frame: spread collapsed to "
                    + String.format(Locale.ROOT, "%.3f", factor)
                    + "x after the HDR ratio ceiling, which is the base frame");
            return false;
        }
        double targetExposureProduct = ((double) source.exposure * source.iso) / factor;
        // Spend the EV on gain first and keep the shutter as close to the rest of
        // the burst as possible. Google's own bracketed bursts do exactly this:
        // in their capture description the ultra-short frame runs 48.3 ms at gain
        // 4.0 while the regular frames run 66.7 ms at gain 44.4 - a 15x exposure
        // difference produced almost entirely by gain. Matching shutter times keeps
        // motion blur comparable across the burst, and differing motion blur is one
        // of the three reasons Google name for bracketed frames being hard to align.
        // Only when ISO is already at the sensor floor is the remaining EV taken
        // from the shutter.
        int isoFloor = getISOLOW();
        long sensorMinimum = getEXPLOW();
        if (PreferenceKeys.isTetModelEnabled()) {
            // Shutter comes from the TET curve applied to the *base* frame's TET and is
            // then held fixed; the bracket offset is carried entirely by gain. A GCam
            // dump shows exactly this: one shutter for the whole burst and a TET factor
            // of 13.33 delivered by gain alone.
            double baseTet = TetModel.toTet(source.exposure, source.iso, isoFloor);
            TetModel.Split baseSplit = TetModel.solve(baseTet, isoFloor, getISOHIGH(),
                    sensorMinimum, Math.min(getEXPHIGH(), shutterCapNs(captureController)));
            pair.exposure = baseSplit.exposureNs;
            long tetIso = Math.round(targetExposureProduct / Math.max(pair.exposure, 1));
            pair.iso = (int) Math.max(isoFloor, Math.min(getISOHIGH(), tetIso));
            Log.i(TAG, "TET model (short): base TET " + baseTet + " -> shutter "
                    + baseSplit.exposureNs + " ns, ISO " + pair.iso);
        } else {
            // Spend the EV on gain first and keep the shutter as close to the rest of
            // the burst as possible. Google's own bracketed bursts do exactly this:
            // in their capture description the ultra-short frame runs 48.3 ms at gain
            // 4.0 while the regular frames run 66.7 ms at gain 44.4 - a 15x exposure
            // difference produced almost entirely by gain. Matching shutter times keeps
            // motion blur comparable across the burst, and differing motion blur is one
            // of the three reasons Google name for bracketed frames being hard to align.
            // Only when ISO is already at the sensor floor is the remaining EV taken
            // from the shutter.
            int gainMatchedIso = (int) Math.round(source.iso / factor);
            pair.iso = Math.max(isoFloor, Math.min(source.iso, gainMatchedIso));
            long shutterMatched = Math.round(targetExposureProduct / Math.max(pair.iso, 1));
            pair.exposure = Math.max(sensorMinimum, Math.min(source.exposure, shutterMatched));
        }
        pair.curlayer = ExpoPair.exposureLayer.Low;
        pair.isHighlightFrame = true;
        pair.isLongFrame = false;
        pair.layerMpy = 1.0f;
        fullpairs.add(pair);

        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, pair.exposure);
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, pair.iso);
        lastSelectedExposure = pair.exposure;
        double actualEv = Math.log(((double) source.exposure * source.iso)
                / ((double) pair.exposure * pair.iso)) / Math.log(2.0);
        Log.i(TAG, "Ultra-short highlight frame: "
                + ExposureIndex.sec2string(ExposureIndex.time2sec(pair.exposure))
                + " ISO " + pair.iso + " (target -" + requestedEv + " EV, actual -"
                + String.format(Locale.ROOT, "%.2f", actualEv) + " EV)");
        return true;
    }


    /**
     * Upper bound on the long frame's shutter, expressed in readout periods so it scales
     * with the sensor. GCam exposes the same limit as camera.shasta_zsl.max_exptime_ms and
     * runs it at two readout periods, which is also what keeps the frame aligned with the
     * viewfinder cadence.
     */
    private static long shutterCapNs(CaptureController captureController) {
        float periods = PreferenceKeys.getLongFrameShutterCapPeriods();
        if (periods <= 0.0f) {
            return Long.MAX_VALUE;
        }
        // Readout time is not exposed through Camera2 on every device; 1/30 s is the
        // cadence the viewfinder runs at and matches the value in the sensor metadata.
        double readoutNs = 1e9 / 30.0;
        return (long) (readoutNs * periods);
    }

    /** Appends one long RAW exposure for clean shadow reconstruction. */
    public static void setLongExpo(CaptureRequest.Builder builder,
                                   CaptureController captureController) {
        applyLinearCapture(builder);
        ExpoPair source = hdrPlusBasePair != null
                ? hdrPlusBasePair : GenerateExpoPair(-1, captureController);
        ExpoPair pair = new ExpoPair(source);
        int requestedEv = Math.max(1, Math.min(8, PreferenceKeys.getLongExposureEvValue()));
        double factor = Math.scalb(1.0, requestedEv);
        // Same ceiling as the short frame, applied to this end of the bracket.
        float maxHdrRatio = PreferenceKeys.getMaxHdrRatio();
        if (maxHdrRatio > 1.0f) {
            double shortFactor = Math.scalb(1.0,
                    Math.max(0, Math.min(8, PreferenceKeys.getShortExposureEvValue())));
            double spread = factor * shortFactor;
            if (spread > maxHdrRatio) {
                double excess = Math.sqrt(spread / maxHdrRatio);
                double clamped = Math.max(1.0, factor / excess);
                Log.i(TAG, "Long frame factor " + factor + " -> " + clamped
                        + " to fit the HDR ratio ceiling " + maxHdrRatio);
                factor = clamped;
            }
        }
        double targetProduct = (double) source.exposure * source.iso * factor;
        // Cap the shutter the way GCam does with camera.shasta_zsl.max_exptime_ms
        // (66.666664 ms on this sensor, i.e. two readout periods): a long frame whose
        // shutter runs several readout periods picks up hand-shake blur that none of
        // the other frames have, and differing motion blur is one of the reasons a
        // bracketed frame will not align. Whatever EV the cap leaves unspent is taken
        // from gain instead.
        long shutterCap = shutterCapNs(captureController);
        if (PreferenceKeys.isTetModelEnabled()) {
            // Same fixed shutter as the short frame, for the same reason: GCam's burst
            // runs one shutter throughout and spreads the bracket with gain. This is the
            // end of the bracket where the current heuristic disagrees with the dump -
            // it stretches the shutter first and only gives the remainder to gain.
            double baseTet = TetModel.toTet(source.exposure, source.iso, getISOLOW());
            TetModel.Split baseSplit = TetModel.solve(baseTet, getISOLOW(), getISOHIGH(),
                    getEXPLOW(), Math.min(getEXPHIGH(), shutterCap));
            pair.exposure = baseSplit.exposureNs;
            long tetIso = Math.round(targetProduct / Math.max(pair.exposure, 1));
            pair.iso = (int) Math.max(getISOLOW(), Math.min(getISOHIGH(), tetIso));
            Log.i(TAG, "TET model (long): base TET " + baseTet + " -> shutter "
                    + baseSplit.exposureNs + " ns, ISO " + pair.iso);
        } else {
            pair.exposure = Math.min(Math.min(getEXPHIGH(), shutterCap),
                    Math.round(pair.exposure * factor));
            int gainMatchedIso = (int) Math.round(targetProduct / Math.max(pair.exposure, 1));
            pair.iso = Math.max(source.iso, Math.min(getISOHIGH(), gainMatchedIso));
        }
        pair.curlayer = ExpoPair.exposureLayer.High;
        pair.isHighlightFrame = false;
        pair.isLongFrame = true;
        pair.layerMpy = (float) (((double) pair.exposure * pair.iso)
                / ((double) source.exposure * source.iso));
        fullpairs.add(pair);

        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, pair.exposure);
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, pair.iso);
        lastSelectedExposure = pair.exposure;
        double actualEv = Math.log(((double) pair.exposure * pair.iso)
                / ((double) source.exposure * source.iso)) / Math.log(2.0);
        Log.i(TAG, "Long shadow frame: "
                + ExposureIndex.sec2string(ExposureIndex.time2sec(pair.exposure))
                + " ISO " + pair.iso + " (target +" + requestedEv + " EV, actual +"
                + String.format(Locale.ROOT, "%.2f", actualEv) + " EV)");
    }
    /**
     * Below this exposure spread the ultra-short frame is indistinguishable from the base
     * frame. 1.05 is a twentieth of a stop - under that the two frames differ by less than
     * the sensor's own ISO quantisation, so nothing is lost by not taking it.
     */
    private static final double ULTRA_SHORT_MIN_FACTOR = 1.05;

    private static double mpy1 = 1.0;
    public static ExpoPair GenerateExpoPair(int step, CaptureController captureController) {
        ExpoPair pair = new ExpoPair(captureController.mPreviewExposureTime, getEXPLOW(), getEXPHIGH(),
                captureController.mPreviewIso, getISOLOW(), getISOHIGH(),getISOAnalog());
        double compensation = Math.pow(2.0,PhotonCamera.getSettings().exposureCompensation);
        pair.normalizeiso100();
        pair.ExpoCompensateLower(1.0/compensation);
        if (PhotonCamera.getSettings().selectedMode == CameraMode.NIGHT)
        {
            mpy1 = 7000.0;
            //if(step%3 == 2) mpy = 1.1;
            //mpy = mpy*1.5;
        } else {
             /*else if(PhotonCamera.getSettings().alignAlgorithm == 1){
                if(step%3 == 1) {
                    pair.curlayer = ExpoPair.exposureLayer.High;
                    mpy = 1.0/1.5;
                }
                if(step%3 == 2) {
                    pair.curlayer = ExpoPair.exposureLayer.Normal;
                    mpy = 1.0;
                }
                if(step%3 == 0) {
                    pair.curlayer = ExpoPair.exposureLayer.Low;
                    mpy = 1.5;
                }
            }*/
            mpy1 = 3000.0;
        }
        if(PhotonCamera.getSettings().selectedMode == CameraMode.RAWVIDEO){
            //mpy1 = 0.0;
            pair.denormalizeSystem();
            return pair;
        }

        // Dynamically update tripod state from gyro to avoid stale exposure caching across modes
        if (PhotonCamera.getGyro() != null) {
            useTripod = PhotonCamera.getGyro().getTripod();
        }

        // Shutter-Priority / Dynamic Low-Light AE Curve - PHOTO and NIGHT only.
        // MOTION/RAWVIDEO already returned above, so framerate-sensitive capture is
        // never affected. Tripod overrides mode when active since it removes the
        // handshake concern that motivates the (shorter) handheld ceilings below.
        long capStart, capEnd;
        if (useTripod) {
            capStart = TRIPOD_CAP_START;
            capEnd = TRIPOD_CAP_END;
        } else if (PhotonCamera.getSettings().selectedMode == CameraMode.NIGHT) {
            capStart = NIGHT_HANDHELD_CAP_START;
            capEnd = NIGHT_HANDHELD_CAP_END;
        } else if (PhotonCamera.getSettings().selectedMode == CameraMode.MOTION) {
            capStart = MOTION_HANDHELD_CAP_START;
            capEnd = MOTION_HANDHELD_CAP_END;
        } else {
            capStart = PHOTO_HANDHELD_CAP_START;
            capEnd = PHOTO_HANDHELD_CAP_END;
        }

        double dynamicFactor = getDynamicScalingFactor();
        capStart = (long) (capStart * dynamicFactor);
        capEnd = (long) (capEnd * dynamicFactor);

        if (PhotonCamera.getSettings().selectedMode == CameraMode.PHOTO && !useTripod) {
            capEnd = Math.min(capEnd, ExposureIndex.sec / 15);
            capStart = Math.min(capStart, capEnd);
        }
        if (PhotonCamera.getSettings().selectedMode == CameraMode.MOTION && !useTripod) {
            capEnd = Math.min(capEnd, ExposureIndex.sec / 60);
            capStart = Math.min(capStart, capEnd);
        }

        pair.applyShutterPriorityCurve(capStart, capEnd, CAP_RAMP_STOPS);

        if (pair.normalizedIso() >= 12700.0/mpy1) {
            pair.ReduceIso();
        }
        if (useTripod) {
            // pair.UseIso(Math.max(pair.isoanalog/6.0,101)); // Replaced by applyShutterPriorityCurve
        }

        // Apply dynamic exposure balance shifting and hard limits (shutter/ISO priority)
        if (captureController != null) {
            float mult = captureController.exposureBalanceMultiplier;
            int isoLimit = captureController.exposureBalanceIsoLimit;
            float shutterLimit = captureController.exposureBalanceShutterLimit;
            CameraMode mode = PhotonCamera.getSettings().selectedMode;
            
            boolean hasMultiplier = (mult != 1.0f);
            boolean hasIsoLimit = (isoLimit != -1);
            boolean hasShutterLimit = (shutterLimit > 0.0f || shutterLimit == -2.0f);

            if ((hasMultiplier || hasIsoLimit || hasShutterLimit) && (mode == CameraMode.PHOTO || mode == CameraMode.NIGHT)) {
                pair.applyExposureBalance(mult, isoLimit, shutterLimit);
            }
        }

        double currentManExp = captureController.getParamController().getCurrentExposureValue();
        double currentManISO = captureController.getParamController().getCurrentISOValue();

        if (currentManExp != 0) {
            pair.exposure = (long) currentManExp;
            pair.isShutterLimited = false;
            pair.isShutterTripodBypassed = false;
            if (!useTripod && captureController != null) {
                long limit = pair.resolveShutterLimit(captureController.exposureBalanceShutterLimit, captureController);
                if (limit < pair.exposurehigh && pair.exposure > limit) {
                    pair.isShutterManualOverLimit = true;
                }
            }
        }

        if (currentManISO != 0) {
            pair.iso = (int) (currentManISO * 100.0 / pair.isolow);
            pair.isIsoLimited = false;
            if (captureController != null && captureController.exposureBalanceIsoLimit != -1) {
                if (pair.iso > pair.resolveIsoLimit(captureController.exposureBalanceIsoLimit)) {
                    pair.isIsoManualOverLimit = true;
                }
            }
        }

        pair.curlayer = ExpoPair.exposureLayer.Normal;
        /*if (step%patternSize == 1 && HDR) {
            pair.ExpoCompensateLower(2.0 / 1.0);
            pair.curlayer = ExpoPair.exposureLayer.Low;
        }*/
        /*if(HDR) {
            pair.ExpoCompensateLowerExpo(2.f);
            pair.ExpoCompensateLower(1.f/2.f);
        }*/
        if (step%patternSize == 0 && HDR) {
            // Set multiplier based on bracketing mode (0=Off, 1=Normal, 2=High)
            int bracketingMode = PreferenceKeys.getBracketingMode();
            pair.layerMpy = 1.f;
            if (bracketingMode == 1) {
                // Normal bracketing (1x, 4x)
                pair.layerMpy = 4.f;
            } else if (bracketingMode == 2) {
                // High bracketing (1x, 8x)
                pair.layerMpy = 8.f;
            }

            if (pair.layerMpy > 1.f) {
                pair.curlayer = ExpoPair.exposureLayer.High;
                if (pair.ExpoCompensateLowerExpo2(1.0 / pair.layerMpy)) {
                    pair.layerMpy = 1.f;
                    pair.curlayer = ExpoPair.exposureLayer.Normal;
                }
            } else {
                pair.curlayer = ExpoPair.exposureLayer.Normal;
            }
        }
        if ((step%patternSize == 1) && HDR) {
            pair.layerMpy = 1.f;
            pair.ExpoCompensateLowerExpo2(1.0 / pair.layerMpy);
            pair.curlayer = ExpoPair.exposureLayer.Normal;
        }
        if (step%patternSize == 2 && HDR) {
            pair.layerMpy = 1.f;
            pair.ExpoCompensateLowerExpo2(1.0 / pair.layerMpy);
            pair.curlayer = ExpoPair.exposureLayer.Normal;
        }

        if (pair.exposure < ExposureIndex.sec / 90 && PhotonCamera.getSettings().eisPhoto) {
            //HDR = true;
        }

        if(step != -1) {
            if (step == 0) pairs.clear();
            if (pairs.size() < patternSize) {
                Log.d(TAG, "Added pair:" + pairs.size());
                pairs.add(pair);
            }
        }
        pair.denormalizeSystem();
        return pair;
    }

    public static double getMPY() {
        return 100.0 / getISOLOW();
    }

    private static int mpyIso(int in) {
        return (int) (in * getMPY());
    }

    private static int getISOHIGH() {
        Object key = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (key == null) return 3200;
        else {
            return (int) ((Range) (key)).getUpper();
        }
    }

    public static int getISOHIGHExt() {
        return mpyIso(getISOHIGH());
    }

    private static int getISOLOW() {
        Object key = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (key == null) return 100;
        else {
            return (int) ((Range) (key)).getLower();
        }
    }
    public static int getISOAnalog() {
        Object key = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
        if (key == null) return 100;
        else {
            return (int)(key);
        }
    }

    public static int getISOLOWExt() {
        return mpyIso(getISOLOW());
    }

    public static long getEXPHIGH() {
        Object key = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (key == null) return ExposureIndex.sec;
        else {
            return (long) ((Range) (key)).getUpper();
        }
    }

    public static long getEXPLOW() {
        Object key = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (key == null) return ExposureIndex.sec / 1000;
        else {
            return (long) ((Range) (key)).getLower();
        }
    }

    private static double getDynamicScalingFactor() {
        // 1. Focal Length Scaling
        double focalLength35mm = 24.0;
        CameraCharacteristics characteristics = CaptureController.mCameraCharacteristics;
        if (characteristics != null) {
            float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (focalLengths != null && focalLengths.length > 0 && sensorSize != null) {
                // Approximate 35mm equivalent: (36mm / sensorWidth) * focalLength
                focalLength35mm = (36.0f / sensorSize.getWidth()) * focalLengths[0];
            }
        }

        // Digital zoom factor
        float zoom = 1.0f;
        CaptureResult result = CaptureController.mPreviewCaptureResult;
        if (result != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Float zoomRatio = result.get(CaptureResult.CONTROL_ZOOM_RATIO);
                if (zoomRatio != null) zoom = zoomRatio;
            } else {
                Rect crop = result.get(CaptureResult.SCALER_CROP_REGION);
                Rect activeArray = characteristics != null ? characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) : null;
                if (crop != null && activeArray != null && crop.width() > 0) {
                    zoom = (float) activeArray.width() / crop.width();
                }
            }
        }
        double effectiveFocalLength = focalLength35mm * zoom;
        // Reciprocal rule baseline (24mm wide). Longer focal length -> smaller factor -> faster shutter.
        double focalFactor = 24.0 / Math.max(effectiveFocalLength, 10.0);

        // 2. Stability Scaling (only if not on a tripod)
        double stabilityFactor = 1.0;
        if (!useTripod && PhotonCamera.getGyro() != null) {
            int shakiness = PhotonCamera.getGyro().getFilteredShakiness();
            if (shakiness > 0) {
                // Steady hands (shakiness ~25) -> up to 4x factor.
                // Shaky hands (shakiness ~400) -> down to 0.25x factor.
                stabilityFactor = 100.0 / Math.max(shakiness, 25);

                // For Motion mode, we must be conservative to avoid subject blur.
                if (PhotonCamera.getSettings().selectedMode == CameraMode.MOTION) {
                    stabilityFactor = Math.min(stabilityFactor, 1.2);
                }
            }
        }

        double combined = focalFactor * stabilityFactor;
        // Clamp total scaling to [0.2x, 2.5x] range to avoid extreme/impossible shutter speeds.
        double finalFactor = Math.max(0.2, Math.min(combined, 2.5));
        Log.v(TAG, "Dynamic AE Factor: " + String.format(Locale.US, "%.2f", finalFactor) +
                " (Focal=" + String.format(Locale.US, "%.2f", effectiveFocalLength) + "mm, " +
                "Stability=" + (PhotonCamera.getGyro() != null ? PhotonCamera.getGyro().getFilteredShakiness() : "N/A") + ")");
        return finalFactor;
    }

    private static long getAutoSafeShutterNs(CaptureController captureController) {
        double efl = 24.0;
        boolean oisActive = false;

        CameraCharacteristics characteristics = CaptureController.mCameraCharacteristics;
        if (characteristics != null) {
            float fl = 4.75f;
            float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focalLengths != null && focalLengths.length > 0) {
                fl = focalLengths[0];
            }

            SizeF sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (sensorSize != null && sensorSize.getWidth() > 0) {
                efl = (36.0f / sensorSize.getWidth()) * fl;
            }

            // Explicit and safe OIS capability check
            boolean hasHardwareOis = false;
            int[] oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            if (oisModes != null) {
                for (int mode : oisModes) {
                    if (mode == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                        hasHardwareOis = true;
                        break;
                    }
                }
            }

            if (hasHardwareOis) {
                oisActive = (captureController == null || captureController.oisMode != 2);
            }
        }

        if (efl <= 0.0) efl = 24.0;

        // Reciprocal rule: 8/EFL if OIS is enabled (+3 stops), 1/EFL without OIS
        double safeSec = (oisActive ? 8.0 : 1.0) / efl;
        return (long) (safeSec * ExposureIndex.sec);
    }


    //==================================Class : ExpoPair==================================//

    public static class ExpoPair {
        public enum exposureLayer{
            Low,
            Normal,
            High
        }
        public exposureLayer curlayer;
        /** Capture role survives radiometric layer reclassification in HdrxProcessor. */
        public boolean isHighlightFrame;
        public boolean isLongFrame;
        public float layerMpy = 1.f;
        public long exposure;
        public int iso;
        long exposurehigh, exposurelow;
        int isolow, isohigh,isoanalog;

        public boolean isIsoLimited = false;
        public boolean isShutterLimited = false;
        public boolean isShutterTripodBypassed = false;
        public boolean isIsoManualOverLimit = false;
        public boolean isShutterManualOverLimit = false;

        public ExpoPair(ExpoPair pair) {
            copyfrom(pair);
        }

        public ExpoPair(long expo, long expl, long exph, int is, int islow, int ishigh, int analog) {
            exposure = expo;
            iso = is;
            exposurehigh = exph;
            exposurelow = expl;
            isolow = islow;
            isohigh = ishigh;
            isoanalog = analog;
        }
        public double Exposure(){
            return ExposureIndex.time2sec(exposure)*iso;
        }
        public void copyfrom(ExpoPair pair) {
            exposure = pair.exposure;
            exposurelow = pair.exposurelow;
            exposurehigh = pair.exposurehigh;
            iso = pair.iso;
            isolow = pair.isolow;
            isohigh = pair.isohigh;
            isoanalog = pair.isoanalog;
            curlayer = pair.curlayer;
            layerMpy = pair.layerMpy;
            isHighlightFrame = pair.isHighlightFrame;
            isLongFrame = pair.isLongFrame;
        }

        public void normalizeiso100() {
            double mpy = 100.0 / isolow;
            iso *= mpy;
            isoanalog *=mpy;
        }

        public void denormalizeSystem() {
            double div = 100.0 / isolow;
            iso /= div;
            isoanalog /=div;
        }
        public float normalizedIso(){
            return (float)iso/isoanalog;
        }
        public void normalize() {
            double div = 100.0 / isolow;
            if (iso / div > isohigh) iso = isohigh;
            if (iso / div < isolow) iso = isolow;
            if (exposure > exposurehigh) exposure = exposurehigh;
            if (exposure < exposurelow) exposure = exposurelow;
        }

        public boolean normalizeCheck() {
            double div = 100.0 / isolow;
            boolean wrongparams = false;
            if (iso / div > isohigh) wrongparams = true;
            if (iso / div < isolow) wrongparams = true;
            if (exposure > exposurehigh) wrongparams = true;
            if (exposure < exposurelow) wrongparams = true;
            return wrongparams;
        }

        public void normalizeISO(){
            double div = 100.0 / isolow;
            if (iso / div > isohigh) {
                double mpy = (iso / div) / isohigh;
                exposure = (long) (exposure * mpy);
                iso = isohigh;
            }
        }

        public void ExpoCompensateLower(double k) {
            iso /= k;
            normalizeISO();
            if (normalizeCheck()) {
                iso *= k;
                exposure /= k;
                if (normalizeCheck()) {
                    exposure *= k;
                    layerMpy = 1.f;
                }
            }
        }

        /**
         * Shutter-Priority / Dynamic Low-Light AE curve.
         *
         * Keeps the platform's own metered brightness target (exposure * iso stays
         * constant) but re-splits it between shutter time and ISO gain: ISO is tried
         * at its minimum first, and only raised once the per-frame shutter time would
         * need to exceed a cap. That cap itself is not fixed - it slides from capStart
         * up to capEnd as the metered scene gets darker (rampStops controls how many
         * stops of extra darkness the full slide takes), which is what makes this a
         * *dynamic* low-light strategy rather than a single handheld/night/tripod
         * threshold switch. The per-mode+tripod shutter ceiling is never exceeded,
         * even in extreme edge cases (e.g. large +exposure compensation in near-total
         * darkness) - if max ISO still isn't enough at that point, the frame comes out
         * a little short of the requested brightness rather than surprising the user
         * with a handheld shot far slower than the active mode calls for.
         *
         * When ISO does need to rise above minimum, it's snapped to the nearest "clean"
         * hardware gain point at or above the bare minimum required - see
         * {@link #snapToCleanIso} - rather than left at whatever continuous value the
         * arithmetic produces, since an off-grid ISO is frequently not pure analog gain
         * on the sensor and reads noisier than a clean stage for no benefit. The trade-off
         * is a slightly shorter exposure than the theoretical maximum (a clean rung is
         * never below the continuous optimum, only ever a bit above it), in exchange for
         * a real, hardware-backed SNR win at whatever ISO we actually land on.
         *
         * @param capStart  per-frame shutter time where we start extending past minimum ISO
         * @param capEnd    per-frame shutter time ceiling in the darkest scenes
         * @param rampStops how many stops darker than capStart's "just enough" point it
         *                  takes to reach capEnd
         */
        public void applyShutterPriorityCurve(long capStart, long capEnd, double rampStops) {
            double totalExposureEnergy = (double) exposure * iso; // proxy for scene darkness: bigger = darker

            // Energy capStart can already deliver at minimum ISO - past this point,
            // minimum ISO alone is no longer enough to hit the metered brightness.
            double energyAtCapStart = (double) capStart * MIN_ISO_NORMALIZED;

            long dynamicCap;
            if (totalExposureEnergy <= energyAtCapStart) {
                dynamicCap = capStart; // plenty of light, no need to extend the shutter at all
            } else {
                double stopsPastStart = log2(totalExposureEnergy / energyAtCapStart);
                double t = Math.max(0.0, Math.min(1.0, stopsPastStart / rampStops));
                dynamicCap = (long) (capStart * Math.pow((double) capEnd / capStart, t)); // geometric slide
            }
            long effectiveCap = Math.min(dynamicCap, exposurehigh); // never ask for more than the sensor allows either

            // Smallest (continuous) ISO that still hits the metered brightness within effectiveCap.
            double isoMinToFit = totalExposureEnergy / effectiveCap;

            if (isoMinToFit <= MIN_ISO_NORMALIZED) {
                iso = MIN_ISO_NORMALIZED; // plenty of light, minimum ISO alone already fits under the cap
            } else {
                long cleanIso = snapToCleanIso(isoMinToFit, true);
                double shutterAtCleanIso = totalExposureEnergy / cleanIso;

                // If snapping up to a "clean" hardware gain stage would drop our shutter time
                // by more than 5% below the cap, prioritize the photon collection (shutter duration)
                // and use the exact ISO required instead.
                if (shutterAtCleanIso < effectiveCap * 0.95) {
                    iso = (int) Math.ceil(isoMinToFit);
                } else {
                    iso = (int) cleanIso;
                }
            }
            exposure = (long) (totalExposureEnergy / iso);

            // Safety clamp, done by hand in normalized-ISO-100 units. Deliberately NOT
            // calling normalize()/normalizeISO() here: those assign the raw isohigh bound
            // straight into this normalized field, which only happens to be unit-correct
            // when the sensor's isolow is exactly 100. Bounding exposure by effectiveCap
            // (not just exposurehigh) keeps the "never exceed the policy cap" guarantee
            // even when snapToCleanIso has to fall back to the sensor's true ISO ceiling.
            if (exposure > effectiveCap) exposure = effectiveCap;
            if (exposure < exposurelow) exposure = exposurelow;
            double isoHighNormalized = isohigh * (100.0 / isolow);
            if (iso > isoHighNormalized) iso = (int) Math.round(isoHighNormalized);
            if (iso < MIN_ISO_NORMALIZED) iso = MIN_ISO_NORMALIZED;

            Log.v(TAG, "ShutterPriorityCurve: energy=" + (long) totalExposureEnergy +
                    " dynamicCap=" + ExposureIndex.sec2string(ExposureIndex.time2sec(dynamicCap)) +
                    " -> exposure=" + ExposureIndex.sec2string(ExposureIndex.time2sec(exposure)) +
                    " iso=" + iso);
        }        

        /**
         * Resolves the effective normalized ISO ceiling based on the configured limit flag/number.
         */
        public double resolveIsoLimit(int isoLimit) {
            if (isoLimit == -4) return Math.max(100.0, (double) isoanalog / 4.0);
            if (isoLimit == -3) return Math.max(100.0, (double) isoanalog / 2.0);
            if (isoLimit == -2) return (double) isoanalog;
            if (isoLimit == -1) return (double) isohigh * (100.0 / isolow);
            return Math.min((double) isohigh, (double) isoLimit) * (100.0 / isolow);
        }

        /**
         * Resolves the effective shutter duration limit in nanoseconds.
         */
        public long resolveShutterLimit(float shutterLimitSec, CaptureController cc) {
            if (shutterLimitSec == -2.0f) return getAutoSafeShutterNs(cc);
            if (shutterLimitSec > 0.0f) return (long) (shutterLimitSec * ExposureIndex.sec);
            return exposurehigh;
        }

        /**
         * Shifts the exposure balance by the given multiplier k (shutter/ISO trade-off).
         * A multiplier > 1.0 reduces shutter duration and increases ISO (freezing motion).
         * A multiplier < 1.0 increases shutter duration and reduces ISO (cleaner image).
         *
         * Uses a Dual-Axis Backtracking Clamping algorithm with Tripod Awareness.
         *
         * @param k               the multiplier to adjust balance
         * @param isoLimit        the configured ISO limit (-1 = Sensor Max, -2 = Max Analog, -3 = Max Analog / 2, -4 = Max Analog / 4, >0 = Custom limit)
         * @param shutterLimitSec the configured shutter duration limit in seconds (-1.0f = Sensor Max, >0 = Custom limit in seconds)
         */
        public void applyExposureBalance(double k, int isoLimit, float shutterLimitSec) {
            isIsoLimited = false;
            isShutterLimited = false;
            isShutterTripodBypassed = false;
            isIsoManualOverLimit = false;
            isShutterManualOverLimit = false;

            // 1. Save target exposure energy
            double targetEnergy = (double) exposure * iso;

            // 2. Apply theoretical shift
            exposure = (long) (exposure / k);
            iso = (int) (iso * k);

            // 3. Resolve bounds using helper methods
            double isoHighNormalized = resolveIsoLimit(isoLimit);
            long userShutterNs = resolveShutterLimit(shutterLimitSec, PhotonCamera.getCaptureController());
            long effectiveExposureHigh = useTripod ? exposurehigh : Math.min(exposurehigh, userShutterNs);

            // 4. ISO limits check with backtracking to exposure
            if (iso > isoHighNormalized) {
                iso = (int) Math.round(isoHighNormalized);
                if (isoLimit != -1) isIsoLimited = true;
                exposure = (long) (targetEnergy / iso);
            } else if (iso < 100) {
                iso = 100;
                exposure = (long) (targetEnergy / iso);
            }

            // 5. Exposure limits check with clean ISO snapping down
            if (exposure > effectiveExposureHigh) {
                exposure = effectiveExposureHigh;
                if ((shutterLimitSec > 0.0f || shutterLimitSec == -2.0f) && !useTripod) isShutterLimited = true;
                double continuousIso = targetEnergy / exposure;
                iso = (int) snapToCleanIso(continuousIso, false);
            } else if (exposure < exposurelow) {
                exposure = exposurelow;
                double continuousIso = targetEnergy / exposure;
                iso = (int) snapToCleanIso(continuousIso, false);
            }

            // 6. Final safety clamps
            if (iso > isoHighNormalized) {
                iso = (int) Math.round(isoHighNormalized);
                if (isoLimit != -1) isIsoLimited = true;
            }
            if (iso < 100) iso = 100;

            if (exposure > effectiveExposureHigh) {
                exposure = effectiveExposureHigh;
                if ((shutterLimitSec > 0.0f || shutterLimitSec == -2.0f) && !useTripod) isShutterLimited = true;
            }
            if (exposure < exposurelow) exposure = exposurelow;

            // 7. Check if tripod mode bypassed the user's handheld shutter limit
            if (useTripod && userShutterNs < exposurehigh && exposure > userShutterNs) {
                isShutterTripodBypassed = true;
                isShutterLimited = false;
            }
        }

        /**
         * Snaps to the nearest ISO the sensor can realize as a clean hardware gain step (normalized ISO-100 basis):
         * the base ISO doubled some number of times, plus the sensor's own reported max-pure-analog gain point
         * ({@code isoanalog} / SENSOR_MAX_ANALOG_SENSITIVITY) inserted as an extra rung even when it doesn't fall
         * on a doubling, since that boundary is real hardware data rather than an assumption about gain-stage spacing.
         * Falls back to the sensor's true ISO ceiling if nothing smaller fits.
         *
         * @param targetIso target normalized ISO value to snap
         * @param snapUp    if true, snaps UP (ceiling, >= targetIso) to guarantee safe exposure duration in auto curves;
         *                  if false, snaps DOWN (floor, <= targetIso) to guarantee pure analog gain without HAL digital
         *                  scaling noise when bounded by shutter limits.
         * @return snapped clean normalized ISO value
         */
        private long snapToCleanIso(double targetIso, boolean snapUp) {
            double isoHighNormalized = isohigh * (100.0 / isolow);
            double isoAnalogNormalized = isoanalog * (100.0 / isolow);

            double[] ladder = new double[16];
            int n = 0;
            for (double rung = MIN_ISO_NORMALIZED; rung <= isoHighNormalized && n < 14; rung *= CLEAN_ISO_STEP_FACTOR) {
                ladder[n++] = rung;
            }
            if (isoAnalogNormalized > MIN_ISO_NORMALIZED && isoAnalogNormalized < isoHighNormalized) {
                ladder[n++] = isoAnalogNormalized;
            }
            ladder[n++] = isoHighNormalized; // true sensor ceiling, always available as a last resort
            java.util.Arrays.sort(ladder, 0, n);

            if (snapUp) {
                for (int i = 0; i < n; i++) {
                    if (ladder[i] >= targetIso) return Math.round(ladder[i]);
                }
                return Math.round(isoHighNormalized);
            } else {
                long result = Math.round(ladder[0]);
                for (int i = 0; i < n; i++) {
                    if (ladder[i] <= targetIso) {
                        result = Math.round(ladder[i]);
                    } else {
                        break;
                    }
                }
                return result;
            }
        }

        private static double log2(double x) {
            return Math.log(x) / Math.log(2.0);
        }

        public void ExpoCompensateLowerExpo(double k) {
            iso /= k;
            if (normalizeCheck()) {
                iso *= k;
                exposure /= k;
                if(normalizeCheck()){
                    exposure *= k;
                    exposure /= Math.sqrt(k);
                    iso /= Math.sqrt(k);
                    if (normalizeCheck()) {
                        exposure *= Math.sqrt(k);
                        iso *= Math.sqrt(k);
                    }
                }
            }
        }

        public boolean ExpoCompensateLowerExpo2(double k) {
            exposure /= k;
            if (normalizeCheck()) {
                exposure *= k;
                iso /= k;
                if(normalizeCheck()){
                    iso *= k;
                    iso /= Math.sqrt(k);
                    exposure /= Math.sqrt(k);
                    if (normalizeCheck()) {
                        iso *= Math.sqrt(k);
                        exposure *= Math.sqrt(k);
                    }
                }
            }
            return normalizeCheck();
        }

        public void MinIso() {
            UseIso(100);
        }

        public void UseIso(double isoUsed) {
            double k = iso / isoUsed;
            ReduceIso(k);
            if (normalizeCheck()) {
                iso *= (double) (exposure) / exposurehigh;
                exposure = exposurehigh;
                if (normalizeCheck()) {
                    iso = isohigh;
                }
            }
        }

        public void ReduceIso() {
            ReduceIso(2.0);
            if (normalizeCheck()) {
                ReduceIso(1.0 / 2);
            }
        }

        public void ReduceIso(double k) {
            iso /= k;
            exposure *= k;
        }

        public void ReduceExpo() {
            ReduceExpo(2.0);
            if (normalizeCheck()) ReduceExpo(1.0 / 2);
        }

        public void ReduceExpo(double k) {
            Log.d(TAG, "ExpoReducing iso:" + iso + " expo:" + ExposureIndex.sec2string(ExposureIndex.time2sec(exposure)));
            iso *= k;
            exposure /= k;
            Log.d(TAG, "ExpoReducing done iso:" + iso + " expo:" + ExposureIndex.sec2string(ExposureIndex.time2sec(exposure)));
        }

        public void FixedExpo(double expo) {
            long expol = ExposureIndex.sec2time(expo);
            double k = (double) exposure / expol;
            ReduceExpo(k);
            Log.d(TAG, "ExpoFixating iso:" + iso + " expo:" + ExposureIndex.sec2string(ExposureIndex.time2sec(exposure)));
            if (normalizeCheck()) ReduceExpo(1 / k);
        }

        public String ExposureString() {
            return ExposureIndex.sec2string(ExposureIndex.time2sec(exposure));
        }
    }
}
