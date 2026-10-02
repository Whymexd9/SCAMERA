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
 * Post-shutter requests of the SCAM HDR LMC hybrid: the N frames come from the ZSL ring at the
 * preview exposure, after the press the camera exposes
 * <ul>
 * <li>one ultrashort frame (Bento, {@link ImageFrame.CaptureRole#EXTRA_SHORT}) at N / factor (LMC: 8),
 *     only when the newest buffered frame clips (or Bento is forced),</li>
 * <li>up to three bracketed frames (Shasta, {@link ImageFrame.CaptureRole#LONG}) at N x 2^ev (LMC: max(long, 4 x short)),
 *     shutter first up to the handheld cap, the rest as gain; skipped when the shutter cannot lengthen by
 *     1.2x (LMC "no benefit") or the ratio exceeds the limit.</li>
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

    /** Preferences of the plan (pref_vivo_nice_hybrid_*; nice_dev.txt overrides without the prefix). */
    public static boolean shastaEnabled() { return PreferenceKeys.niceInternalSwitch("hybrid_shasta", true); }
    public static int bracketCount() { return Math.max(0, Math.min(3, Math.round(PreferenceKeys.niceInternalValue("hybrid_shasta_frames", 2f)))); }
    public static double bracketEv() { return Math.max(1, Math.min(4, PreferenceKeys.niceInternalValue("hybrid_shasta_ev", 2f))); }
    /** 0 off, 1 auto (needs clipping in the buffered frame), 2 force. */
    public static int bentoMode() { return Math.max(0, Math.min(2, Math.round(PreferenceKeys.niceInternalValue("hybrid_bento", 1f)))); }
    public static double ultrashortFactor() { return Math.max(2, Math.min(32, PreferenceKeys.niceInternalValue("hybrid_bento_factor", 8f))); }
    public static float bentoTriggerClip() { return Math.max(0f, Math.min(0.1f, PreferenceKeys.niceInternalValue("hybrid_bento_trigger", 0.0005f))); }
    public static double maxBracketRatio() { return Math.max(2, Math.min(100, PreferenceKeys.niceInternalValue("hybrid_shasta_max_ratio", 32f))); }

    public static HybridPlan build(long nShutterNs, int nIso, float clipFraction, CameraCharacteristics characteristics) {
        Range<Long> times = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Integer> isos = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (times == null || isos == null || nShutterNs <= 0 || nIso <= 0)
            throw new IllegalStateException("SCAM HDR: нет экспозиции превью или диапазонов сенсора");
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
            if (ratio < 0.5) out.add(new Request(ImageFrame.CaptureRole.EXTRA_SHORT, ns, iso, ratio));
            else why.append(" us skipped (sensor floor)");
        } else why.append(bento == 0 ? " bento off" : String.format(Locale.ROOT, " no clipping (%.4f)", clipFraction));
        // Bracketed (Shasta): shutter first within the handheld cap, the rest as gain.
        final int count = shastaEnabled() ? bracketCount() : 0;
        if (count > 0) {
            final double ev = bracketEv(), target = n * Math.pow(2, ev);
            final long cap = Math.min(times.getUpper(), Math.max(nShutterNs, HANDHELD_SHUTTER_CAP_NS));
            long ns = Math.max(times.getLower(), Math.min(cap, Math.round(target / nIso)));
            ns = snapAntibanding(ns, nShutterNs);
            int iso = (int) Math.max(isos.getLower(), Math.min(isos.getUpper(), Math.round(target / ns)));
            double ratio = (double) ns * iso / n;
            if (ns < nShutterNs * 1.2) why.append(String.format(Locale.ROOT, " brackets skipped (shutter %.2fx < 1.2x)", (double) ns / nShutterNs));
            else if (ratio > maxBracketRatio()) why.append(String.format(Locale.ROOT, " brackets skipped (ratio %.1f)", ratio));
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
        if (!(tag instanceof ImageFrame.NiceCaptureTag)) throw new IllegalStateException("SCAM HDR: запрос без роли");
        int index = ((ImageFrame.NiceCaptureTag) tag).index;
        if (index < 0 || index >= requests.size()) throw new IllegalStateException("SCAM HDR: индекс запроса вне плана");
        Request r = requests.get(index);
        Long ns = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (ns == null || iso == null || ns <= 0 || iso <= 0) throw new IllegalStateException("SCAM HDR: нет экспозиции Camera2 в результате");
        double ev = Math.abs(Math.log((double) ns * iso / ((double) r.shutterNs * r.iso)) / Math.log(2));
        if (ev > VERIFY_TOLERANCE_EV)
            throw new IllegalStateException(String.format(Locale.ROOT, "SCAM HDR: выдержка/ISO RAW не совпали с планом гибрида (%s: ISO %d/%d, shutter %d/%d, %.2f EV)",
                    r.role, iso, r.iso, ns, r.shutterNs, ev));
    }
}
