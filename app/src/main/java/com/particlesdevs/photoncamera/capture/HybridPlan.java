package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.util.Range;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Post-shutter requests of the LMC hybrid (its own route, not SCAM HDR): the N frames come from the ZSL ring at the
 * preview exposure, after the press the camera exposes
 * <ul>
 * <li>one or two ultrashort frames (Bento, {@link ImageFrame.CaptureRole#EXTRA_SHORT}) at N / factor (LMC: one at 8),
 *     only when the newest buffered frame clips (or Bento is forced),</li>
 * <li>up to five bracketed frames (Shasta, {@link ImageFrame.CaptureRole#LONG}) at N x 2^ev, gain first at the N shutter,
 *     the shutter lengthens only where the sensor's gain range ends (up to the handheld cap); skipped when the ratio is below
 *     1.5 or above the limit. As ArkCam 1.23 / LMC 9.6 on the same Oppo by day (research/lmc/device/oppo_debug_20261004):
 *     5 frames at 10 ms x analog gain 2.22 (TET x1.9 of N), sharpness 97-106 % of the base, none discarded, 82 % of the
 *     pixels weighted from them. The former shutter-first rule (33 ms x ISO 145 for N = 10 ms) blurred them by hand shake
 *     and ship vibration: 33-82 % of the base sharpness, below the 80 % gate in every shot, never merged.</li>
 * </ul>
 * Capture order: ultrashort first (closest in time to the buffered base), bracketed frames after it.
 */
public final class HybridPlan {
    public static final class Request {
        public final ImageFrame.CaptureRole role;
        public final long shutterNs;
        public final int iso;
        /** Exposure product relative to N. */
        public final double ratio;
        Request(ImageFrame.CaptureRole role, long shutterNs, int iso, double ratio) {
            this.role = role; this.shutterNs = shutterNs; this.iso = iso; this.ratio = ratio;
        }
        @Override public String toString() {
            return String.format(Locale.ROOT, "%s %.3fms*ISO%d (x%.3f)", role, shutterNs / 1e6, iso, ratio);
        }
    }
    private static final long HANDHELD_SHUTTER_CAP_NS = 125_000_000L;
    private static final double VERIFY_TOLERANCE_EV = 0.4;

    public final List<Request> requests;
    public final long nShutterNs;
    public final int nIso;
    public final String description;

    private HybridPlan(List<Request> requests, long nShutterNs, int nIso, String description) {
        this.requests = Collections.unmodifiableList(requests);
        this.nShutterNs = nShutterNs; this.nIso = nIso; this.description = description;
    }

    /** Preferences of the plan: the hybrid's own pref_lmc_hybrid_* (nice_dev.txt "hybrid_<key>" overrides), never SCAM HDR's. */
    public static boolean shastaEnabled() { return PreferenceKeys.hybridSwitch("shasta", true); }
    public static int bracketCount() { return Math.max(0, Math.min(5, Math.round(PreferenceKeys.hybridValue("shasta_frames", 5f)))); }
    public static double bracketEv() { return Math.max(1, Math.min(4, PreferenceKeys.hybridValue("shasta_ev", 1f))); }
    /** 0 off, 1 auto (needs clipping in the buffered frame), 2 force. */
    public static int bentoMode() { return Math.max(0, Math.min(2, Math.round(PreferenceKeys.hybridValue("bento", 1f)))); }
    /** Ultrashort exposure = N / factor; LMC ultrashort_tet_factor 8 (default). */
    public static double ultrashortFactor() { return Math.max(2, Math.min(16, PreferenceKeys.hybridValue("bento_factor", 8f))); }
    /**
     * Ultrashort frames per shot: 1 = LMC 9.6, 2 (default) = a second one at the same exposure; the worker merges both inside the
     * mask (half the noise of the x8 replacement, the hand shake between them fills the R/B lattice of a single Bayer frame).
     */
    public static int bentoFrames() { return Math.max(1, Math.min(2, Math.round(PreferenceKeys.hybridValue("bento_frames", 2f)))); }
    public static float bentoTriggerClip() { return Math.max(0f, Math.min(0.1f, PreferenceKeys.hybridValue("bento_trigger", 0.0005f))); }
    public static double maxBracketRatio() { return Math.max(2, Math.min(100, PreferenceKeys.hybridValue("shasta_max_ratio", 32f))); }

    public static HybridPlan build(long nShutterNs, int nIso, float clipFraction, CameraCharacteristics characteristics) {
        Range<Long> times = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Integer> isos = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (times == null || isos == null || nShutterNs <= 0 || nIso <= 0)
            throw new IllegalStateException("Hybrid: нет экспозиции превью или диапазонов сенсора");
        final double n = (double) nShutterNs * nIso;
        List<Request> out = new ArrayList<>();
        StringBuilder why = new StringBuilder();
        // Ultrashort (Bento): lower the gain first, then the shutter.
        final int bento = bentoMode();
        if (bento == 2 || (bento == 1 && clipFraction > bentoTriggerClip())) {
            final double factor = ultrashortFactor(), target = n / factor;
            int iso = (int) Math.max(isos.getLower(), Math.min(nIso, Math.round(target / nShutterNs)));
            long ns = Math.max(times.getLower(), Math.min(nShutterNs, Math.round(target / iso)));
            double ratio = (double) ns * iso / n;
            if (ratio < 0.5) for (int i = bentoFrames(); i > 0; i--) out.add(new Request(ImageFrame.CaptureRole.EXTRA_SHORT, ns, iso, ratio));
            else why.append(" us skipped (sensor floor)");
        } else why.append(bento == 0 ? " bento off" : String.format(Locale.ROOT, " no clipping (%.4f)", clipFraction));
        // Bracketed (Shasta): gain first at the N shutter (no extra motion blur), the shutter lengthens only beyond the sensor's
        // gain range, within the handheld cap.
        final int count = shastaEnabled() ? bracketCount() : 0;
        if (count > 0) {
            final double ev = bracketEv(), target = n * Math.pow(2, ev);
            int iso = (int) Math.max(isos.getLower(), Math.min(isos.getUpper(), Math.round(target / nShutterNs)));
            long ns = nShutterNs;
            if ((double) ns * iso < target * 0.95) {
                final long cap = Math.min(times.getUpper(), Math.max(nShutterNs, HANDHELD_SHUTTER_CAP_NS));
                ns = snapAntibanding(Math.max(nShutterNs, Math.min(cap, Math.round(target / iso))), nShutterNs);
            }
            double ratio = (double) ns * iso / n;
            if (ratio > maxBracketRatio()) why.append(String.format(Locale.ROOT, " brackets skipped (ratio %.1f)", ratio));
            else if (ratio < 1.5) why.append(String.format(Locale.ROOT, " brackets skipped (ratio %.2f)", ratio));
            else for (int i = 0; i < count; i++) out.add(new Request(ImageFrame.CaptureRole.LONG, ns, iso, ratio));
        } else why.append(" shasta off");
        // Nothing to add after the shutter: one more frame at N keeps the capture path uniform.
        if (out.isEmpty()) out.add(new Request(ImageFrame.CaptureRole.NORMAL, nShutterNs, nIso, 1.0));
        String description = String.format(Locale.ROOT, "hybrid plan: N=%.3fms*ISO%d clip=%.4f requests=%s%s",
                nShutterNs / 1e6, nIso, clipFraction, out, why);
        return new HybridPlan(out, nShutterNs, nIso, description);
    }

    /** Long shutters snap to whole flicker periods (pref_antibanding_hz_key), never below the N shutter. */
    private static long snapAntibanding(long ns, long nShutterNs) {
        int hz;
        try { hz = PreferenceKeys.getAntibandingHz(); } catch (RuntimeException e) { hz = 0; }
        if (hz <= 0) return ns;
        final long period = Math.round(1e9 / hz);
        if (ns < 2 * period) return ns;
        long snapped = (ns / period) * period;
        return snapped >= nShutterNs ? snapped : ns;
    }

    public void apply(CaptureRequest.Builder builder, int index) {
        Request r = requests.get(index);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false);
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, r.shutterNs);
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, r.iso);
    }

    /** The result must be the requested exposure (sensor rounding allowed), else the frame is not the planned one. */
    public void verify(CaptureRequest request, CaptureResult result) {
        Object tag = request.getTag();
        if (!(tag instanceof ImageFrame.NiceCaptureTag)) throw new IllegalStateException("Hybrid: запрос без роли");
        int index = ((ImageFrame.NiceCaptureTag) tag).index;
        if (index < 0 || index >= requests.size()) throw new IllegalStateException("Hybrid: индекс запроса вне плана");
        Request r = requests.get(index);
        Long ns = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (ns == null || iso == null || ns <= 0 || iso <= 0) throw new IllegalStateException("Hybrid: нет экспозиции Camera2 в результате");
        double ev = Math.abs(Math.log((double) ns * iso / ((double) r.shutterNs * r.iso)) / Math.log(2));
        if (ev > VERIFY_TOLERANCE_EV)
            throw new IllegalStateException(String.format(Locale.ROOT, "Hybrid: выдержка/ISO RAW не совпали с планом Hybrid (%s: ISO %d/%d, shutter %d/%d, %.2f EV)",
                    r.role, iso, r.iso, ns, r.shutterNs, ev));
    }
}
