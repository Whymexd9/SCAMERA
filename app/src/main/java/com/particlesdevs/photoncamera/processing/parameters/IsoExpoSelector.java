package com.particlesdevs.photoncamera.processing.parameters;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.TonemapCurve;
import com.particlesdevs.photoncamera.util.Log;
import android.util.Range;

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
    private static final double CLEAN_ISO_STEP_FACTOR = 2.0; // hardware analog gain stages are conventionally doublings of the base ISO











    /**
     * Below this exposure spread the ultra-short frame is indistinguishable from the base
     * frame. 1.05 is a twentieth of a stop - under that the two frames differ by less than
     * the sensor's own ISO quantisation, so nothing is lost by not taking it.
     */


    public static ExpoPair GenerateExpoPair(int step, CaptureController captureController) {
        ExpoPair pair = new ExpoPair(captureController.mPreviewExposureTime, getEXPLOW(), getEXPHIGH(),
                captureController.mPreviewIso, getISOLOW(), getISOHIGH(),getISOAnalog());
        // Both routes (LMC hybrid, SCAM HDR) use the metered sensor exposure, without the former legacy AE curves.
        if (pair.exposure <= 0 || pair.iso <= 0)
            throw new IllegalStateException("NICE: missing preview exposure/ISO");
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
        /**
         * Enforces a final shutter ceiling while retaining as much of the
         * requested exposure energy as the sensor ISO range permits: the
         * exposure is clamped and ISO raised by the same factor, so the frame
         * keeps its brightness and loses only the motion blur.
         * From matthew777777/PhotonCamera 1a7cf8c4.
         */
        public void capExposurePreservingEnergy(long maxExposure) {
            long effectiveMax = Math.max(exposurelow, Math.min(maxExposure, exposurehigh));
            if (exposure <= effectiveMax) return;

            double targetEnergy = (double) exposure * iso;
            double isoHighNormalized = (double) isohigh * (100.0 / isolow); // the sensor's highest ISO
            double requiredIso = targetEnergy / effectiveMax;
            iso = (int) Math.ceil(Math.max(MIN_ISO_NORMALIZED,
                    Math.min(isoHighNormalized, requiredIso)));
            exposure = effectiveMax;
            isShutterLimited = true;

            Log.v(TAG, "FinalShutterCap: max="
                    + ExposureIndex.sec2string(ExposureIndex.time2sec(effectiveMax))
                    + " -> exposure="
                    + ExposureIndex.sec2string(ExposureIndex.time2sec(exposure))
                    + " iso=" + iso);
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
