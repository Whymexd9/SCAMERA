package com.particlesdevs.photoncamera.processing.color;

import android.hardware.camera2.CaptureResult;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Locale;

/**
 * P212g (W6 = M6, the verifier's variant): whose neutral a vivo mosaic photo takes. P121 replaced the camera's neutral of every
 * mosaic shot with its own estimate (and set ownNeutral, which blocks the ISP's colour matrix), on the premise that the ISP reads a
 * Quad / Tetra mosaic as Bayer and sees a grey scene - never confirmed on a phone (PLAN.md P121). On the vivo native FULLRES routes
 * the HAL's 3A runs and its neutral is a live illuminant point, usually the better one: X300 Ultra tele 0.475 / 0.599 against the
 * plain session's 0.4805 / 0.5967 16 s later and our 0.466 / 0.610 (SCAMERA-debug.log (7).txt:500, :1151, :605); X200 Ultra main
 * 30830 0.373 / 0.670 against our 0.354 / 0.705.
 * <ul>
 * <li>{@link #ROUTE_VIVO_NATIVE} (a vivo native FULLRES session, its viewfinder the mode itself): the reference result's HAL
 * neutral is kept (ownNeutral false: the ISP's matrix, or the plain-session table, PlainColourMemory) when its AWB is CONVERGED or
 * LOCKED and the neutral lies within {@link #CURVE_DISTANCE} of the camera's curve (MosaicNeutral: its learned points, or the DNG
 * prior while it has none); else P121's projection of our estimate (a HAL off the curve, as a stably wrong Tetra neutral would be,
 * not converged, or a camera without any curve to tell).</li>
 * <li>{@link #ROUTE_STILL_ONLY} (P208, the X100 / X300 Ultra 200 MP: the viewfinder is the camera's normal mode, its ISP AWB valid,
 * the full-size stream takes the stills only - the stock camera's method, Desktop/x300u_stock200.txt): the median (ln) of the
 * viewfinder's neutrals of the last {@link #WINDOW_MS} before the press with AWB CONVERGED / LOCKED ({@link Ring}); without any,
 * the still's own HAL neutral by the rule above, else P121.</li>
 * <li>anything else (the 12 MP ISZ modules of any brand, the OPPO native route): P121 as before; P366: in a RAW-only session whose
 * ISP runs no 3A ({@link #ispIdle}) the ISP teacher's neutral where it has learned enough (MosaicTeacher.use, MosaicNeutral.taught).</li>
 * </ul>
 * No trust score and no fitted variances (the verifier: agreement between the HAL and our estimate cannot say which is wrong, and
 * constants fitted to the ~10 logged cases would only reproduce them). scam_dev.txt "mosaic_awb_resolver 0": P121 on every route;
 * "mosaic_awb 0" keeps its meaning (the camera's neutral on every mosaic shot).
 */
public final class MosaicNeutralResolver {
    private MosaicNeutralResolver() {}

    /**
     * P315: {@link #ROUTE_SENSOR_MODE}, an OPPO / OnePlus / realme session in a module's picked sensor mode (SensorModePick, the
     * agingtest key): the HAL's 3A runs there as in the plain mode. The OPPO Find X8 Ultra's 2x ISZ (mode 8): its neutrals 0.446 /
     * 0.589 and 0.435 / 0.605, 0.005 ln from the camera's curve and equal to the plain mode's of the same scene, while P121's estimate
     * read the LED glow on the white case (0.985 / 0.856, 0.745 ln off the curve) and gave cyan whites, and its ownNeutral dropped
     * the ISP matrix for the placeholder DNG matrices (owner, 2026-10-21: «обесцвеченные и с странным бб»). Same rule as the vivo
     * native route.
     */
    public static final int ROUTE_NONE = 0, ROUTE_VIVO_NATIVE = 1, ROUTE_STILL_ONLY = 2, ROUTE_SENSOR_MODE = 3;
    /** P315: a P121 estimate farther than this (ln) from every learned illuminant of the camera's curve is not taken as one. */
    public static final double OFF_LOCUS = 0.4;
    /** ln: how far from the camera's curve a HAL neutral may lie and still be kept. */
    static final double CURVE_DISTANCE = 0.1;
    /** The viewfinder's neutrals before the press the still-only route takes (ms), the ring's size. */
    public static final long WINDOW_MS = 1_000L;
    static final int RING = 32;

    /** scam_dev.txt "mosaic_awb_resolver" (default on). */
    public static boolean on() {
        return com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("mosaic_awb_resolver", true)
                && com.particlesdevs.photoncamera.settings.PreferenceKeys.mosaicWbCorrections(); // P223
    }

    /** What the press knew: the shot's route and, on the still-only route, the viewfinder's neutral (r, 1, b; null: none). */
    public static final class Press {
        public final int route;
        @Nullable public final float[] viewfinder;
        /** Viewfinder results the median is of, their spread (ln, the largest of r and b). */
        public final int results;
        public final float spread;

        Press(int route, @Nullable float[] viewfinder, int results, float spread) {
            this.route = route;
            this.viewfinder = viewfinder;
            this.results = results;
            this.spread = spread;
        }

        public static Press vivoNative() {
            return new Press(ROUTE_VIVO_NATIVE, null, 0, 0f);
        }

        /** P315: an OPPO-family session in a picked sensor mode (its HAL's 3A runs). */
        public static Press sensorMode() {
            return new Press(ROUTE_SENSOR_MODE, null, 0, 0f);
        }

        @Override public String toString() {
            return route == ROUTE_VIVO_NATIVE ? "vivo native" : route == ROUTE_SENSOR_MODE ? "sensor mode (OPPO family)" : route == ROUTE_STILL_ONLY ? viewfinder == null ? "still-only, no converged viewfinder result"
                    : String.format(Locale.ROOT, "still-only, viewfinder %.3f / %.3f of %d results (spread %.3f)", viewfinder[0], viewfinder[2],
                    results, spread) : "none";
        }
    }

    /**
     * The viewfinder's neutrals of a still-only session (fed from onCaptureCompleted): the last {@link #RING} results' camera,
     * elapsedRealtime, neutral and whether their AWB was AUTO and CONVERGED / LOCKED. Thread-safe (the camera thread offers, the
     * press reads).
     */
    public static final class Ring {
        private final String[] camera = new String[RING];
        private final long[] ms = new long[RING];
        private final float[] r = new float[RING], b = new float[RING];
        private final boolean[] converged = new boolean[RING];
        private int next, size;

        public synchronized void clear() {
            next = size = 0;
            Arrays.fill(camera, null);
        }

        /** A viewfinder result of {@code cameraId} at elapsedRealtime {@code nowMs}. */
        public void offer(@Nullable String cameraId, long nowMs, @Nullable CaptureResult result) {
            if (cameraId == null || result == null) return;
            final android.util.Rational[] n;
            final Integer mode, state;
            try {
                n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
                mode = result.get(CaptureResult.CONTROL_AWB_MODE);
                state = result.get(CaptureResult.CONTROL_AWB_STATE);
            } catch (RuntimeException e) {
                return;
            }
            if (n == null || n.length < 3 || !(n[1].floatValue() > 0f)) return;
            offer(cameraId, nowMs, n[0].floatValue() / n[1].floatValue(), n[2].floatValue() / n[1].floatValue(),
                    mode != null && mode == CaptureResult.CONTROL_AWB_MODE_AUTO && converged(state));
        }

        synchronized void offer(String cameraId, long nowMs, float rg, float bg, boolean awbConverged) {
            if (!usable(rg) || !usable(bg)) return;
            camera[next] = cameraId;
            ms[next] = nowMs;
            r[next] = rg;
            b[next] = bg;
            converged[next] = awbConverged;
            next = (next + 1) % RING;
            size = Math.min(RING, size + 1);
        }

        /** The still-only press of {@code cameraId} at elapsedRealtime {@code pressMs}: the median of its converged results then. */
        public synchronized Press stillOnly(@Nullable String cameraId, long pressMs) {
            final double[] lr = new double[size], lb = new double[size];
            int n = 0;
            for (int i = 0; i < size; i++) {
                if (cameraId == null || !cameraId.equals(camera[i]) || !converged[i]) continue;
                final long age = pressMs - ms[i];
                if (age < 0 || age > WINDOW_MS) continue;
                lr[n] = Math.log(r[i]);
                lb[n] = Math.log(b[i]);
                n++;
            }
            if (n == 0) return new Press(ROUTE_STILL_ONLY, null, 0, 0f);
            final double mr = median(lr, n), mb = median(lb, n);
            double spread = 0;
            for (int i = 0; i < n; i++) spread = Math.max(spread, Math.max(Math.abs(lr[i] - mr), Math.abs(lb[i] - mb)));
            return new Press(ROUTE_STILL_ONLY, new float[]{(float) Math.exp(mr), 1f, (float) Math.exp(mb)}, n, (float) spread);
        }
    }

    /** The resolver's choice: the neutral (r, 1, b), where from (log text). */
    public static final class Decision {
        public final float[] neutral;
        public final String how;

        Decision(float[] neutral, String how) {
            this.neutral = neutral;
            this.how = how;
        }
    }

    /**
     * The neutral a mosaic photo takes instead of P121's projection, or null for P121: {@code press} the press's route (null: none),
     * {@code hal} the reference result's neutral (r, g, b) and its AWB state, {@code curveDistance} its distance (ln) from the
     * camera's curve (NaN: no curve to tell).
     */
    @Nullable
    public static Decision decide(@Nullable Press press, @Nullable float[] hal, @Nullable Integer awbState, float curveDistance) {
        if (press == null || press.route == ROUTE_NONE || !on()) return null;
        if (press.route == ROUTE_STILL_ONLY && press.viewfinder != null)
            return new Decision(press.viewfinder.clone(), String.format(Locale.ROOT,
                    "still-only route: the normal-mode viewfinder's neutral %.3f / %.3f (median of %d converged results in the last %d ms, spread %.3f)",
                    press.viewfinder[0], press.viewfinder[2], press.results, WINDOW_MS, press.spread));
        final String route = press.route == ROUTE_STILL_ONLY ? "still-only route without a converged viewfinder result"
                : press.route == ROUTE_SENSOR_MODE ? "sensor-mode route (OPPO family)" : "vivo native route";
        if (hal == null || hal.length < 3 || !(hal[1] > 0f) || !usable(hal[0] / hal[1]) || !usable(hal[2] / hal[1])) return null;
        if (!converged(awbState) || !(curveDistance <= CURVE_DISTANCE)) return null;
        final float r = hal[0] / hal[1], b = hal[2] / hal[1];
        return new Decision(new float[]{r, 1f, b}, String.format(Locale.ROOT,
                "%s: the HAL's neutral %.3f / %.3f kept (AWB %s, %.3f from the camera's curve)", route, r, b,
                awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED ? "locked" : "converged", curveDistance));
    }

    /** Why {@link #decide} gave P121 for a route it handles (log text); empty for a route it does not. */
    public static String whyNot(@Nullable Press press, @Nullable float[] hal, @Nullable Integer awbState, float curveDistance) {
        if (press == null || press.route == ROUTE_NONE) return "";
        if (!on()) return "mosaic_awb_resolver 0";
        if (hal == null || hal.length < 3 || !(hal[1] > 0f) || !usable(hal[0] / hal[1]) || !usable(hal[2] / hal[1])) return "no HAL neutral";
        if (!converged(awbState)) return "the HAL's AWB not converged (state " + awbState + ")";
        if (Float.isNaN(curveDistance)) return "no curve of the camera to check the HAL's neutral against";
        return String.format(Locale.ROOT, "the HAL's neutral %.3f / %.3f is %.3f from the camera's curve (more than %.2f)",
                hal[0] / hal[1], hal[2] / hal[1], curveDistance, CURVE_DISTANCE);
    }

    /**
     * The distance (ln) of neutral {@code n} (r, g, b) from camera {@code id}'s curve as MosaicNeutral projects onto it (the locus,
     * or the P121 polyline with "mosaic_awb_locus 0"); NaN when the camera has none. P366 (review): the curve its learned points fit
     * (MosaicNeutral.onLearnedCurve), not the one P366's slope guard puts a photo's statistic on - a converged HAL neutral under a
     * light the camera learned stays within CURVE_DISTANCE of it, as before.
     */
    public static float curveDistance(String id, @Nullable float[] n, @Nullable MosaicNeutral.Prior prior) {
        if (n == null || n.length < 3 || !(n[1] > 0f) || !usable(n[0] / n[1]) || !usable(n[2] / n[1])) return Float.NaN;
        final MosaicNeutral.Result q = MosaicNeutral.onLearnedCurve(id, n[0] / n[1], n[2] / n[1], prior, "HAL");
        return q == null ? Float.NaN : q.distance;
    }

    /**
     * P315: the camera's neutral instead of P121's estimate when that estimate lies more than {@link #OFF_LOCUS} from every learned
     * illuminant of the camera's curve (a coloured light's glow, not a white point: the X8U's LED-lit white case, 0.745 / 0.872 ln)
     * while the camera's own neutral lies on the curve (within {@link #CURVE_DISTANCE}) and away from the curve's point nearest raw
     * grey (an ISP reading the mosaic as Bayer would converge there); the log text, null to keep P121. P366 (review): all three
     * against the curve the camera's learned points fit ({@code ownDistance}: MosaicNeutral.Result#learnedDistance).
     */
    @Nullable
    public static String offLocus(String id, float ownDistance, @Nullable float[] hal, @Nullable MosaicNeutral.Prior prior) {
        if (!(ownDistance > OFF_LOCUS) || !on() || hal == null || hal.length < 3 || !(hal[1] > 0f)) return null;
        final float halDistance = curveDistance(id, hal, prior);
        if (!(halDistance <= CURVE_DISTANCE)) return null;
        final MosaicNeutral.Result foot = MosaicNeutral.onLearnedCurve(id, 1f, 1f, prior, "raw grey");
        if (foot == null) return null;
        final double r = hal[0] / hal[1], b = hal[2] / hal[1];
        final double fromFoot = Math.hypot(Math.log(r / foot.neutral[0]), Math.log(b / foot.neutral[2]));
        if (!(fromFoot > CURVE_DISTANCE)) return null;
        return String.format(Locale.ROOT, "P121's estimate is %.3f from the camera's curve (more than %.1f: a coloured light, not an"
                + " illuminant): the camera's neutral %.3f / %.3f kept (%.3f from the curve, %.3f from its raw-grey point)",
                ownDistance, OFF_LOCUS, r, b, halDistance, fromFoot);
    }

    /** P366: results a burst needs, the sensor time they span, how far (ln) their gains may stray from the median, for {@link #ispIdle}. */
    static final int IDLE_RESULTS = 4;
    static final long IDLE_SPAN_NS = 300_000_000L;
    static final double IDLE_SPREAD = 0.005;

    /**
     * P366 (owner, 2026-10-27: «вот такие фото получаю с исз, исправь»): whether a mosaic burst comes from a RAW-only session whose ISP
     * runs no 3A, where the camera's neutral says nothing (MosaicTeacher.useMode 1): every result has AWB AUTO, the identity
     * COLOR_CORRECTION_TRANSFORM (no OPPO com.oplus.custom.ccm: an OPPO ISP with an identity transform renders through that one) and
     * COLOR_CORRECTION_GAINS within {@link #IDLE_SPREAD} ln of their median (R / G and B / G), at least {@link #IDLE_RESULTS}
     * results over {@link #IDLE_SPAN_NS} of sensor time. The owner's X200U camera 4 ISZ burst (forceSensorMode 3, the vivo SDK lease,
     * no ISP stream): 14 results (8 from the ring, 6 after the press) over more than 0.5 s, the identity, R 2.4336-2.4365 / B 1.5693
     * (0.12 %, the HAL's 1/1024 steps), the same 7 s later while the scene's statistic moved; a running ISP (camera 3's plain results a moment before: CCM [153 -19 -6 | -17 175 -30 | 0 -55 184] / 128)
     * never reports the identity on vivo. Only asked for a burst without a route (the vivo native and OPPO sensor-mode routes run
     * their 3A). Returns the log text, null when the ISP may be running (or the results do not tell).
     */
    @Nullable
    public static String ispIdle(@Nullable java.util.List<CaptureResult> results) {
        if (results == null) return null;
        final java.util.ArrayList<IdleSample> samples = new java.util.ArrayList<>(results.size());
        for (CaptureResult r : results) samples.add(r == null ? null : IdleSample.of(r));
        return ispIdleOf(samples);
    }

    /**
     * P370 (review): what {@link #ispIdle} reads of one result - its timestamp, whether it passes (AWB AUTO, the identity transform
     * without an OPPO matrix, positive gains) and its ln R / G, ln B / G - so a viewfinder keeps these instead of a second of
     * CaptureResults (ViewfinderMosaicWb).
     */
    public static final class IdleSample {
        @Nullable final Long ts;
        /** The timestamp could not be read (the whole test then says nothing, as a result that throws did). */
        final boolean tsFailed;
        /** It passes the per-result conditions (false: AWB not AUTO, no or another transform, an OPPO matrix, no gains, a throw). */
        final boolean passes;
        final double lnR, lnB;

        private IdleSample(@Nullable Long ts, boolean tsFailed, boolean passes, double lnR, double lnB) {
            this.ts = ts;
            this.tsFailed = tsFailed;
            this.passes = passes;
            this.lnR = lnR;
            this.lnB = lnB;
        }

        public static IdleSample of(CaptureResult r) {
            final Long ts;
            try {
                ts = r.get(CaptureResult.SENSOR_TIMESTAMP);
            } catch (RuntimeException e) {
                return new IdleSample(null, true, false, 0, 0);
            }
            try {
                final Integer awb = r.get(CaptureResult.CONTROL_AWB_MODE);
                if (awb == null || awb != CaptureResult.CONTROL_AWB_MODE_AUTO) return new IdleSample(ts, false, false, 0, 0);
                final android.hardware.camera2.params.ColorSpaceTransform t = r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
                if (t == null) return new IdleSample(ts, false, false, 0, 0);
                final float[] m = new float[9];
                for (int row = 0; row < 3; row++) for (int c = 0; c < 3; c++) m[row * 3 + c] = t.getElement(c, row).floatValue();
                if (!OplusCcm.identity(m) || oplusCcm(r)) return new IdleSample(ts, false, false, 0, 0);
                final android.hardware.camera2.params.RggbChannelVector g = r.get(CaptureResult.COLOR_CORRECTION_GAINS);
                if (g == null) return new IdleSample(ts, false, false, 0, 0);
                final double green = 0.5 * (g.getGreenEven() + g.getGreenOdd());
                if (!(green > 0) || !(g.getRed() > 0) || !(g.getBlue() > 0)) return new IdleSample(ts, false, false, 0, 0);
                return new IdleSample(ts, false, true, Math.log(g.getRed() / green), Math.log(g.getBlue() / green));
            } catch (RuntimeException e) {
                return new IdleSample(ts, false, false, 0, 0);
            }
        }

        @Nullable public Long timestamp() {
            return ts;
        }
    }

    /** {@link #ispIdle} on the results' samples (in their order; a null sample is skipped as a null result). */
    @Nullable
    public static String ispIdleOf(@Nullable Iterable<IdleSample> samples) {
        if (samples == null) return null;
        final java.util.ArrayList<double[]> gains = new java.util.ArrayList<>(); // {ln R/G, ln B/G}
        final java.util.HashSet<Long> seen = new java.util.HashSet<>();
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        for (IdleSample s : samples) {
            if (s == null) continue;
            if (s.tsFailed) return null;
            final Long ts = s.ts;
            if (ts == null || !seen.add(ts)) continue;
            if (!s.passes) return null;
            gains.add(new double[]{s.lnR, s.lnB});
            first = Math.min(first, ts);
            last = Math.max(last, ts);
        }
        if (gains.size() < IDLE_RESULTS || last - first < IDLE_SPAN_NS) return null;
        final double[] lr = new double[gains.size()], lb = new double[gains.size()];
        for (int i = 0; i < gains.size(); i++) {
            lr[i] = gains.get(i)[0];
            lb[i] = gains.get(i)[1];
        }
        final double mr = median(lr, lr.length), mb = median(lb, lb.length);
        double spread = 0;
        for (int i = 0; i < lr.length; i++) spread = Math.max(spread, Math.max(Math.abs(lr[i] - mr), Math.abs(lb[i] - mb)));
        if (spread > IDLE_SPREAD) return null;
        return String.format(Locale.ROOT, "the ISP runs no 3A: %d results over %d ms, the identity colour transform, gains R %.3f / B %.3f"
                + " frozen within %.2f %%", gains.size(), (last - first) / 1_000_000L, Math.exp(mr), Math.exp(mb), 100 * spread);
    }

    /** P366: whether the result carries a usable OPPO ISP matrix (com.oplus.custom.ccm): its ISP renders, whatever the transform says. */
    private static boolean oplusCcm(CaptureResult r) {
        try {
            return OplusCcm.parse(r.get(new CaptureResult.Key<>(OplusCcm.TAG_NAME, byte[].class))) != null;
        } catch (RuntimeException noTag) {
            return false;
        }
    }

    static boolean converged(@Nullable Integer state) {
        return state != null && (state == CaptureResult.CONTROL_AWB_STATE_CONVERGED || state == CaptureResult.CONTROL_AWB_STATE_LOCKED);
    }

    private static boolean usable(float v) {
        return v > 0.05f && v < 20f && !Float.isInfinite(v);
    }

    private static double median(double[] v, int n) {
        final double[] s = Arrays.copyOf(v, n);
        Arrays.sort(s);
        return n % 2 == 1 ? s[n / 2] : 0.5 * (s[n / 2 - 1] + s[n / 2]);
    }
}
