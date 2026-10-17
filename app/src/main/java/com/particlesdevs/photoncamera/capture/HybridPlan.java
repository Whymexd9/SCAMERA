package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.util.Range;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.settings.DeviceDefaults;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * Post-shutter requests of the SCAM Hybrid (its own route, not SCAM HDR): the N frames come from the ZSL ring at the
 * preview exposure, after the press the camera exposes
 * <ul>
 * <li>one or two ultrashort frames (Bento, {@link ImageFrame.CaptureRole#EXTRA_SHORT}) at N / factor (SCAM: one at 8),
 *     only when the newest buffered frame clips (or Bento is forced),</li>
 * <li>up to five bracketed frames (Shasta, {@link ImageFrame.CaptureRole#LONG}) at N x 2^ev, gain first at the N shutter,
 *     the shutter lengthens only where the sensor's gain range ends (up to the handheld cap); skipped when the ratio is below
 *     1.5 or above the limit. As ArkCam 1.23 / SCAM 9.6 on the same Oppo by day (research/scam/device/oppo_debug_20261004):
 *     5 frames at 10 ms x analog gain 2.22 (TET x1.9 of N), sharpness 97-106 % of the base, none discarded, 82 % of the
 *     pixels weighted from them. The former shutter-first rule (33 ms x ISO 145 for N = 10 ms) blurred them by hand shake
 *     and ship vibration: 33-82 % of the base sharpness, below the 80 % gate in every shot, never merged.</li>
 * </ul>
 * Capture order: ultrashort first (closest in time to the buffered base), bracketed frames after it.
 * <p>
 * P27: a frame never costs the shot. One that came back at another exposure than planned keeps the role its measured exposure
 * gives ({@link #classify}); with no buffered N the frames are taken after the shutter ({@link #buildNormalBack}); without any
 * known N exposure the camera's own AE exposes them ({@link #autoExposure}).
 */
public final class HybridPlan {
    public static final class Request {
        public final ImageFrame.CaptureRole role;
        public final long shutterNs;
        public final int iso;
        /** Exposure product relative to N. */
        public final double ratio;
        /** P27: no N exposure is known; the camera's AE exposes this frame (its measured exposure is used as is). */
        public final boolean autoExposure;
        Request(ImageFrame.CaptureRole role, long shutterNs, int iso, double ratio) {
            this(role, shutterNs, iso, ratio, false);
        }
        Request(ImageFrame.CaptureRole role, long shutterNs, int iso, double ratio, boolean autoExposure) {
            this.role = role; this.shutterNs = shutterNs; this.iso = iso; this.ratio = ratio; this.autoExposure = autoExposure;
        }
        @Override public String toString() {
            return autoExposure ? role + " AE" : String.format(Locale.ROOT, "%s %.3fms*ISO%d (x%.3f)", role, shutterNs / 1e6, iso, ratio);
        }
    }
    private static final long HANDHELD_SHUTTER_CAP_NS = 125_000_000L;
    /**
     * P98: the HAL's motion-safe exposure while it reports motion (HalMotion.motionCapNs), set by the camera around one plan
     * build; 0 = none. It lowers the handheld cap of a bracketed frame's shutter, never below the N shutter.
     */
    static volatile long sMotionCapNs;

    public static void setMotionCap(long ns) { sMotionCapNs = Math.max(0, ns); }

    /** The handheld shutter cap of a plan with N at {@code nShutterNs} and the HAL's motion cap {@code motionCapNs}. */
    static long handheldCap(long nShutterNs, long motionCapNs) {
        long cap = HANDHELD_SHUTTER_CAP_NS;
        if (motionCapNs > 0) cap = Math.min(cap, motionCapNs);
        return Math.max(nShutterNs, cap);
    }
    private static final double VERIFY_TOLERANCE_EV = 0.4;
    /**
     * P27: a frame that missed its plan is merged as an N frame when it is at most 0.5 EV darker or 0.05 EV brighter than N.
     * Never clearly brighter: the merge accumulates the clipped samples of N frames (only bracketed frames skip them), so a
     * brighter N donor would put clipped values into highlights the base still resolves.
     */
    static final double NORMAL_DARKER_RATIO = Math.pow(2, -0.5), NORMAL_BRIGHTER_RATIO = 1.035;

    public final List<Request> requests;
    public final long nShutterNs;
    public final int nIso;
    public final String description;

    private HybridPlan(List<Request> requests, long nShutterNs, int nIso, String description) {
        this.requests = Collections.unmodifiableList(requests);
        this.nShutterNs = nShutterNs; this.nIso = nIso; this.description = description;
    }

    /** Preferences of the plan: the hybrid's own pref_scam_hybrid_* (scam_dev.txt "hybrid_<key>" overrides), never SCAM HDR's. */
    public static boolean shastaEnabled() { return PreferenceKeys.hybridSwitch("shasta", true); }
    public static int bracketCount() { return Math.max(0, Math.min(5, Math.round(PreferenceKeys.hybridValue("shasta_frames", 3f)))); }
    /** P68: Shasta "always": the frames are taken whatever their ratio to N (above 1.2) and merged unless they moved (worker). */
    public static boolean shastaForced() { return PreferenceKeys.getHybridShastaMode() == 2; }
    public static double bracketEv() { return Math.max(1, Math.min(4, PreferenceKeys.hybridValue("shasta_ev", 1f))); }
    /** 0 off, 1 auto (needs clipping in the buffered frame), 2 force. */
    public static int bentoMode() { return Math.max(0, Math.min(2, Math.round(PreferenceKeys.hybridValue("bento", 2f)))); }
    /** Ultrashort exposure = N / factor; SCAM ultrashort_tet_factor 8 (default). */
    public static double ultrashortFactor() { return Math.max(2, Math.min(16, PreferenceKeys.hybridValue("bento_factor", 8f))); }
    /**
     * Ultrashort frames per shot: 1 = SCAM 9.6, 2 = a second one at the same exposure; the worker merges both inside the
     * mask (half the noise of the x8 replacement, the hand shake between them fills the R/B lattice of a single Bayer frame);
     * 3 (default since P68) = the more than 3 non-bracketed frames Mochi needs.
     */
    public static int bentoFrames() {
        int count = Math.round(PreferenceKeys.hybridValue("bento_frames", 3f));
        if (PreferenceKeys.getHybridMochi() > 0) count = Math.max(count, 3);
        return Math.max(1, Math.min(3, count));
    }
    public static float bentoTriggerClip() { return Math.max(0f, Math.min(0.1f, PreferenceKeys.hybridValue("bento_trigger", 0.0005f))); }
    public static double maxBracketRatio() { return Math.max(2, Math.min(100, PreferenceKeys.hybridValue("shasta_max_ratio", 32f))); }

    public static HybridPlan build(long nShutterNs, int nIso, float clipFraction, CameraCharacteristics characteristics) {
        return build(nShutterNs, nIso, clipFraction, characteristics, null);
    }

    /**
     * P27: {@code limits} (may be null) caps the manual ISO / shutter at what this camera's HAL was seen to honour
     * (ExposureLimits); with nothing learned the plan is the one of the declared ranges.
     */
    public static HybridPlan build(long nShutterNs, int nIso, float clipFraction, CameraCharacteristics characteristics, ExposureLimits limits) {
        boolean dcg;
        try {
            dcg = Dcg14Mode.active(characteristics);
        } catch (RuntimeException e) {
            dcg = false;
        }
        return build(nShutterNs, nIso, clipFraction, characteristics, limits, dcg);
    }

    /**
     * {@code gainFloorAtN}: the HAL gives no gain below the N frames' (P163, the X300 Ultra's «DCG 14 бит» session, owner's log
     * 2026-10-16 14:49: the main camera's DCG mode answered ISO 1336 to every request of ISO 167, the AE itself sat at 1339
     * where the plain mode had ISO 179 a few seconds before; the three ultrashort frames came at x0.998 of N, merged as N, and
     * Bento had nothing - the window stayed clipped). The ultrashort then keeps the N gain and takes its factor from the
     * shutter, which the DCG mode honours to the nanosecond.
     */
    static HybridPlan build(long nShutterNs, int nIso, float clipFraction, CameraCharacteristics characteristics, ExposureLimits limits,
                            boolean gainFloorAtN) {
        Range<Long> times = capped(characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE), limits == null ? Long.MAX_VALUE : limits.shutterCap());
        Range<Integer> isos = capped(characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE), limits == null ? Integer.MAX_VALUE : limits.isoCap());
        if (times == null || isos == null || nShutterNs <= 0 || nIso <= 0)
            throw new IllegalStateException(Lang.t("SHDR: нет экспозиции превью или диапазонов сенсора", "SHDR: no preview exposure or sensor ranges"));
        final double n = (double) nShutterNs * nIso;
        List<Request> out = new ArrayList<>();
        StringBuilder why = new StringBuilder();
        // Ultrashort (Bento): lower the gain first, then the shutter.
        // P71 (owner, 2026-10-10): on the Pixel 7 "always" takes the ultrashort frames only where the buffered frame clips, as
        // auto (its forced Bento ran on masks of 0.001-0.01 % of the frame: ~130 ms a shot with nothing to replace)
        // P79: the Redmi Note 11 Pro as well (3 of its 17 frames went through every GPU pass with weight 0: "would fall back: not
        // enough clipping" in each of the owner's shots).
        final int bento = bentoMode() == 2 && (DeviceDefaults.pixel7() || DeviceDefaults.redmiNote11Pro()) ? 1 : bentoMode();
        if (bento == 2 || (bento == 1 && clipFraction > bentoTriggerClip())) {
            final double factor = ultrashortFactor(), target = n / factor;
            final int floor = gainFloorAtN ? Math.max(isos.getLower(), nIso) : isos.getLower();
            int iso = (int) Math.max(floor, Math.min(nIso, Math.round(target / nShutterNs)));
            long ns = Math.max(times.getLower(), Math.min(nShutterNs, Math.round(target / iso)));
            double ratio = (double) ns * iso / n;
            if (ratio < 0.5) for (int i = bentoFrames(); i > 0; i--) out.add(new Request(ImageFrame.CaptureRole.EXTRA_SHORT, ns, iso, ratio));
            else why.append(" us skipped (sensor floor)");
            if (gainFloorAtN) why.append(" DCG: ultrashort by the shutter");
        } else why.append(bento == 0 ? " bento off" : String.format(Locale.ROOT, " no clipping (%.4f)", clipFraction));
        // Bracketed (Shasta): gain first at the N shutter (no extra motion blur), the shutter lengthens only beyond the sensor's
        // gain range, within the handheld cap.
        final int count = shastaEnabled() ? bracketCount() : 0;
        if (count > 0) {
            final double ev = bracketEv(), target = n * Math.pow(2, ev);
            int iso = (int) Math.max(isos.getLower(), Math.min(isos.getUpper(), Math.round(target / nShutterNs)));
            long ns = nShutterNs;
            if ((double) ns * iso < target * 0.95) {
                final long cap = Math.min(times.getUpper(), handheldCap(nShutterNs, sMotionCapNs));
                ns = snapAntibanding(Math.max(nShutterNs, Math.min(cap, Math.round(target / iso))), nShutterNs);
            }
            double ratio = (double) ns * iso / n;
            if (ratio > maxBracketRatio() && !shastaForced()) why.append(String.format(Locale.ROOT, " brackets skipped (ratio %.1f)", ratio));
            else if (ratio < 1.2) why.append(String.format(Locale.ROOT, " brackets skipped (ratio %.2f)", ratio));
            else for (int i = 0; i < count; i++) out.add(new Request(ImageFrame.CaptureRole.LONG, ns, iso, ratio));
            if (sMotionCapNs > 0) why.append(String.format(Locale.ROOT, " HAL motion: shutter cap %.1fms", handheldCap(nShutterNs, sMotionCapNs) / 1e6));
        } else why.append(" shasta off");
        // Nothing to add after the shutter: one more frame at N keeps the capture path uniform.
        if (out.isEmpty()) out.add(new Request(ImageFrame.CaptureRole.NORMAL, nShutterNs, nIso, 1.0));
        String description = String.format(Locale.ROOT, "hybrid plan: N=%.3fms*ISO%d clip=%.4f requests=%s%s",
                nShutterNs / 1e6, nIso, clipFraction, out, why);
        return new HybridPlan(out, nShutterNs, nIso, description);
    }

    /**
     * P27, Hybrid with an empty ZSL ring (camera just opened, AE ramp, every ring RAW packed): {@code normals} N frames after
     * the shutter, then the Bento / Shasta frames of {@link #build}. The N ISO stays inside the sensor range, the shutter
     * carries the rest of the N exposure (up to the handheld cap).
     */
    public static HybridPlan buildNormalBack(long nShutterNs, int nIso, float clipFraction, CameraCharacteristics characteristics, int normals) {
        return buildNormalBack(nShutterNs, nIso, clipFraction, characteristics, normals, null);
    }

    public static HybridPlan buildNormalBack(long nShutterNs, int nIso, float clipFraction, CameraCharacteristics characteristics, int normals,
                                             ExposureLimits limits) {
        return buildNormalBack(nShutterNs, nIso, clipFraction, characteristics, normals, limits, 0);
    }

    /**
     * {@code motionCapNs} > 0 («Движение»): the N frames keep the viewfinder's ISO (the HAL gave it, only a learned cap lowers it)
     * and a shutter no longer than the N shutter or this ceiling; what is left of the exposure stays missing (the worker scales
     * by the ratio): a darker frame, never a smeared one. Was: the ISO clamped to the declared range and the shutter stretched
     * up to the handheld cap (X200 Ultra after a shot: 4 ms at ISO 20000 became 25 ms at the declared ISO 3200).
     */
    public static HybridPlan buildNormalBack(long nShutterNs, int nIso, float clipFraction, CameraCharacteristics characteristics, int normals,
                                             ExposureLimits limits, long motionCapNs) {
        if (nShutterNs <= 0 || nIso <= 0) return autoExposure(normals);
        Range<Long> times = characteristics == null ? null : capped(characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                limits == null ? Long.MAX_VALUE : limits.shutterCap());
        Range<Integer> isos = characteristics == null ? null : capped(characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                limits == null ? Integer.MAX_VALUE : limits.isoCap());
        final boolean motion = motionCapNs > 0;
        int iso = motion ? Math.max(1, Math.min(nIso, limits == null ? Integer.MAX_VALUE : limits.isoCap()))
                : isos == null ? nIso : Math.max(isos.getLower(), Math.min(isos.getUpper(), nIso));
        long ns = Math.round((double) nShutterNs * nIso / iso);
        final long top = Math.max(nShutterNs, motion ? motionCapNs : HANDHELD_SHUTTER_CAP_NS);
        if (times != null) ns = Math.max(times.getLower(), Math.min(Math.min(times.getUpper(), top), ns));
        else if (motion) ns = Math.min(top, ns);
        final double ratio = (double) ns * iso / ((double) nShutterNs * nIso);
        List<Request> out = new ArrayList<>();
        for (int i = 0; i < Math.max(1, normals); i++) out.add(new Request(ImageFrame.CaptureRole.NORMAL, ns, iso, ratio));
        String extrasText;
        try {
            HybridPlan extras = build(nShutterNs, nIso, clipFraction, characteristics, limits);
            for (Request r : extras.requests) if (r.role != ImageFrame.CaptureRole.NORMAL) out.add(r);
            extrasText = extras.description;
        } catch (RuntimeException e) {
            extrasText = "no Bento / Shasta (" + e.getMessage() + ")";
        }
        return new HybridPlan(out, nShutterNs, nIso, "hybrid normal-back: " + out + " | " + extrasText);
    }

    /** P84b: the N frames of a hand-set long shutter (the owner: the series may take as long as it needs). */
    static final int MANUAL_NORMALS = 4;

    /**
     * P84b (owner, 2026-10-11: «всё равно делай брекет кадры, бенто и шаста, так чтобы они приносили пользу для серии»): the
     * hand-set long shutter after the press (PSL). N frames at exactly {@code shutterNs} / {@code iso} (the camera honoured 0.5 and
     * 4 s on the X200 Ultra although it declares 0.33 s, so no clamp to the declared range); Bento always (a long exposure clips
     * where the 1/5 s viewfinder frame did not, so the ring's clipping says nothing); every Shasta LONG frame of the plan. The
     * series takes as long as it needs (owner); the 4 s shot's camera error was the session reconfiguration (P84c), not its length.
     */
    public static HybridPlan manualLong(long shutterNs, int iso, float clipFraction, CameraCharacteristics characteristics,
                                        ExposureLimits limits) {
        List<Request> bento = new ArrayList<>(), longs = new ArrayList<>();
        String extrasText;
        try {
            HybridPlan extras = build(shutterNs, iso, Math.max(clipFraction, 1f), characteristics, limits);
            for (Request r : extras.requests) {
                if (r.role == ImageFrame.CaptureRole.LONG) longs.add(r);
                else if (r.role != ImageFrame.CaptureRole.NORMAL) bento.add(r);
            }
            extrasText = extras.description;
        } catch (RuntimeException e) {
            extrasText = "no Bento / Shasta (" + e.getMessage() + ")";
        }
        final int normals = MANUAL_NORMALS;
        List<Request> out = new ArrayList<>();
        for (int i = 0; i < normals; i++) out.add(new Request(ImageFrame.CaptureRole.NORMAL, shutterNs, iso, 1.0));
        out.addAll(bento);
        // Shasta: the plan's brightness ratio from a longer shutter at the same ISO (more light, the point of the frame), the
        // gain only for what the module's shutter ceiling cannot give; the declared 0.33 s of the X200 Ultra had made it ISO x2
        final long ceiling = characteristics == null ? shutterNs : Math.max(shutterNs, ExposureRanges.shutter(characteristics).getUpper());
        final int isoMax = characteristics == null ? iso : Math.max(iso, ExposureRanges.iso(characteristics, null).getUpper());
        for (Request r : longs) out.add(manualShasta(shutterNs, iso, r.ratio, ceiling, isoMax));
        return new HybridPlan(out, shutterNs, iso, "hybrid manual long shutter: " + out + " | " + extrasText);
    }

    /** P84b: a Shasta frame {@code ratio} times N: the shutter first (up to {@code ceilingNs}), then the gain (up to {@code isoMax}). */
    static Request manualShasta(long shutterNs, int iso, double ratio, long ceilingNs, int isoMax) {
        final long ns = Math.max(shutterNs, Math.min(ceilingNs, Math.round(shutterNs * ratio)));
        final int gain = (int) Math.max(iso, Math.min(isoMax, Math.round(iso * ratio * shutterNs / ns)));
        return new Request(ImageFrame.CaptureRole.LONG, ns, gain, (double) ns * gain / ((double) shutterNs * iso));
    }

    private static Range<Long> capped(Range<Long> r, long cap) {
        if (r == null || cap >= r.getUpper()) return r;
        return new Range<>(r.getLower(), Math.max(r.getLower(), cap));
    }
    private static Range<Integer> capped(Range<Integer> r, int cap) {
        if (r == null || cap >= r.getUpper()) return r;
        return new Range<>(r.getLower(), Math.max(r.getLower(), cap));
    }

    /** P27: one N frame after the shutter, the plan when Bento / Shasta cannot be planned. */
    /**
     * P113 «Движение» (owner, 2026-10-13: «не могу снять быстро подряд например 5 фото … таким же быстрым как моментальный
     * снимок в стоке»): the ring's frames only, nothing after the shutter (the stock snapshot mode takes its frames from the
     * buffer as well). No HAL flush, lead or AE restore frame, no series to wait for: the shot is complete at the press and the
     * shutter is free for the next one.
     */
    public static HybridPlan zslOnly(long nShutterNs, int nIso) {
        return new HybridPlan(new ArrayList<>(), nShutterNs, nIso,
                String.format(Locale.ROOT, "hybrid plan: N=%.3fms*ISO%d ring frames only (Motion mode: nothing after the shutter)",
                        nShutterNs / 1e6, nIso));
    }

    /**
     * P198: this plan with {@code n} more N frames (its N exposure) ahead of its own requests: a 50 MP ZSL shot whose ring holds
     * fewer N frames than asked takes the rest right after the shutter (the plan's roles keep their order after them).
     */
    public HybridPlan withLeadingNormals(int n) {
        if (n <= 0 || nShutterNs <= 0 || nIso <= 0) return this;
        List<Request> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Request(ImageFrame.CaptureRole.NORMAL, nShutterNs, nIso, 1.0));
        out.addAll(requests);
        return new HybridPlan(out, nShutterNs, nIso, description + " + " + n + " N after the shutter");
    }

    public static HybridPlan single(long nShutterNs, int nIso) {
        if (nShutterNs <= 0 || nIso <= 0) return autoExposure(1);
        List<Request> out = new ArrayList<>();
        out.add(new Request(ImageFrame.CaptureRole.NORMAL, nShutterNs, nIso, 1.0));
        return new HybridPlan(out, nShutterNs, nIso, String.format(Locale.ROOT, "hybrid single N=%.3fms*ISO%d", nShutterNs / 1e6, nIso));
    }

    /** P27: no N exposure is known at all: {@code count} frames exposed by the camera's own AE. */
    public static HybridPlan autoExposure(int count) {
        List<Request> out = new ArrayList<>();
        for (int i = 0; i < Math.max(1, count); i++) out.add(new Request(ImageFrame.CaptureRole.NORMAL, 0, 0, 1.0, true));
        return new HybridPlan(out, 0, 0, "hybrid AE frames: " + out.size() + " (no N exposure known)");
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
        if (r.autoExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
            builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false);
            return;
        }
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false);
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, r.shutterNs);
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, r.iso);
    }

    /**
     * P27: the role the frame of {@code request} really has. Within 0.4 EV of its plan (sensor rounding): the planned role, so a
     * shot whose plan worked merges exactly as before. Otherwise by its measured exposure against N: x1.5 or more bracketed,
     * x0.5 or less ultrashort, between {@link #NORMAL_DARKER_RATIO} and {@link #NORMAL_BRIGHTER_RATIO} an N frame (the owner's
     * 'LONG: ISO 320/640' frames: the HAL capped the gain, the frames are N exposures and are merged as such); null: no role
     * fits (or no metadata), the frame is dropped. Never throws.
     */
    public ImageFrame.CaptureRole classify(CaptureRequest request, CaptureResult result) {
        Object tag = request == null ? null : request.getTag();
        if (!(tag instanceof ImageFrame.ScamCaptureTag) || result == null) return null;
        int index = ((ImageFrame.ScamCaptureTag) tag).index;
        if (index < 0 || index >= requests.size()) return null;
        Request r = requests.get(index);
        Long ns = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (ns == null || iso == null || ns <= 0 || iso <= 0) return null;
        if (r.autoExposure) return r.role;
        double planEv = Math.abs(Math.log((double) ns * iso / ((double) r.shutterNs * r.iso)) / Math.log(2));
        if (planEv <= VERIFY_TOLERANCE_EV) return r.role;
        double ratio = (double) ns * iso / ((double) nShutterNs * nIso);
        ImageFrame.CaptureRole role = ratio >= 1.5 ? ImageFrame.CaptureRole.LONG
                : ratio <= 0.5 ? ImageFrame.CaptureRole.EXTRA_SHORT
                : ratio >= NORMAL_DARKER_RATIO && ratio <= NORMAL_BRIGHTER_RATIO ? ImageFrame.CaptureRole.NORMAL : null;
        com.particlesdevs.photoncamera.util.Log.w("SCAM_CAPTURE", String.format(Locale.ROOT,
                "hybrid: %s #%d delivered x%.3f of N (plan x%.3f; ISO %d/%d, shutter %d/%d, %.2f EV off) -> %s",
                r.role, index, ratio, r.ratio, iso, r.iso, ns, r.shutterNs, planEv, role == null ? "dropped" : "used as " + role));
        return role;
    }
}
