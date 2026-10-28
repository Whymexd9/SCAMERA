package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

/**
 * P383 (owner, 2026-10-28, a screen recording of the X200 Ultra's RAW viewfinder after ArkCam's: «экспозиция меняется не плавно, а
 * ступенчато»): the tone the viewfinder shows between two of its AE runs. Before, LiveWhyTone moved its shown exposure once per AE
 * run (every AE_EVERY = 4 drawn frames, by 0.35 of the gap) and the fused curve and the AgX / clip values jumped to each run's
 * result: on the recording steps of 0.12, 0.11, 0.11, 0.08, 0.05, 0.04 EV every fourth frame and nothing between (ArkCam: 0.02 -
 * 0.09 EV every frame). Now every drawn frame moves all of it towards the last result, by time: a critically damped pair of lags
 * ({@link #TAU_S} each, the exact solution over the frame's dt, so 10 and 30 frames a second ease alike) - it starts gently (6 % of
 * a gap in the first frame at 30 a second), moves at most ~10 % of it a frame and is 90 % there after ~0.47 s (the old law: 35 %
 * at once, 0.8 s at 30 frames a second, 2.4 s at 10). The exposures in log2 (a ratio eases as a ratio), the curve's table and the
 * other values linearly.
 * <p>
 * The exposure eases in the scene's terms (FrameExposure): the target is log2 ae + log2 of the metered frame's exposure, and a frame
 * is drawn at {@link #aeFor} its own exposure - a move of the camera's exposure is cancelled in its frame. Pure Java, tested on the
 * host.
 */
final class ToneEase {
    static final double TAU_S = 0.12;
    /** A frame after a stall (a shot's merge, a pause) eases as if it came this soon after the last: no jump. */
    static final long MAX_DT_NS = 100_000_000L;
    /** A curve entry closer than this to its target is not uploaded again. */
    static final float CURVE_SETTLED = 1e-5f;
    /**
     * The drawn gain stays inside these: a guard against a runaway exposure, never a limit of the photo's own ae (its darkening limit
     * goes down to 0.1 - «Мин. затемнение» - and its boost with the film's exposure up to 9 x 2).
     */
    static final float AE_MIN = 0.05f, AE_MAX = 32f;
    /** The values: log2 of the scene's ae and film ae, log2 of the four exposures, clip, the AgX slope, sp, tp, sat, min / max EV, ev. */
    private static final int AE = 0, FILM = 1, EM = 2, CLIP = 6, SLOPE = 7, SP = 8, TP = 9, SAT = 10, MIN_EV = 11, MAX_EV = 12, EV = 13, N = 14;

    private boolean on, curveOn, curveMoved;
    private final float[] x = new float[N], lag = new float[N], target = new float[N];
    /** The shown exposures of the fusion (vf_why 2's guided filter). */
    final float[] em = new float[4];
    float clip, slope, sp, tp, sat, minEv, maxEv, ev;
    /** The shown fused curve (vf_why 1), FusedCurve.SIZE entries. */
    final float[] curve = new float[FusedCurve.SIZE];
    private final float[] curveLag = new float[FusedCurve.SIZE];

    /** e^(-dt / TAU) and dt / TAU e^(-dt / TAU) of a frame drawn {@code dtNs} after the last (dt up to MAX_DT_NS). */
    static float[] factors(long dtNs) {
        final double t = Math.max(0, Math.min(dtNs, MAX_DT_NS)) / 1e9 / TAU_S, e = Math.exp(-t);
        return new float[]{(float) e, (float) (t * e)};
    }

    /** A new context or session: the next result is shown at once. */
    void reset() {
        on = curveOn = curveMoved = false;
    }

    boolean on() {
        return on;
    }

    /** The gain to draw a frame of log2 exposure {@code logP} with (the photo's ae once it settled on that frame). */
    float aeFor(double logP) {
        final double a = Math.pow(2.0, x[AE] - logP);
        return (float) Math.max(AE_MIN, Math.min(AE_MAX, a));
    }

    /** P383 (vf_ae_trace): log2 of the scene's ae as shown and as the last result asked. */
    double logAe() {
        return x[AE];
    }

    double targetLogAe() {
        return target[AE];
    }

    /** The film's exposure over ours (WhyAe.filmAe / ae), as shown. */
    float filmOverAe() {
        return (float) Math.pow(2.0, x[FILM] - x[AE]);
    }

    /**
     * {@code dtNs} towards {@code r} metered on a frame of log2 exposure {@code logP} ({@code filmAe}: WhyAe.filmAe of it,
     * {@code exposures}: WhyAe.exposures); the first at once.
     */
    void toward(WhyAe.Result r, float filmAe, float[] exposures, double logP, long dtNs) {
        target[AE] = (float) (log2(r.ae) + logP);
        target[FILM] = (float) (log2(filmAe) + logP);
        for (int k = 0; k < 4; k++) target[EM + k] = (float) log2(exposures[k]);
        target[CLIP] = r.clip;
        target[SLOPE] = r.slope;
        target[SP] = r.sp;
        target[TP] = r.tp;
        target[SAT] = r.sat;
        target[MIN_EV] = r.minEv;
        target[MAX_EV] = r.maxEv;
        target[EV] = r.ev;
        if (!on) {
            System.arraycopy(target, 0, x, 0, N);
            System.arraycopy(target, 0, lag, 0, N);
            on = true;
        } else {
            step(x, lag, target, N, factors(dtNs));
        }
        for (int k = 0; k < 4; k++) em[k] = (float) Math.pow(2.0, x[EM + k]);
        clip = x[CLIP];
        slope = x[SLOPE];
        sp = x[SP];
        tp = x[TP];
        sat = x[SAT];
        minEv = x[MIN_EV];
        maxEv = x[MAX_EV];
        ev = x[EV];
    }

    /** {@code dtNs} of the curve towards {@code target} (the first at once); {@link #curveMoved} says whether to upload it. */
    void curveToward(float[] target, long dtNs) {
        if (!curveOn) {
            System.arraycopy(target, 0, curve, 0, curve.length);
            System.arraycopy(target, 0, curveLag, 0, curve.length);
            curveOn = curveMoved = true;
            return;
        }
        final float[] f = factors(dtNs);
        boolean moved = false;
        for (int i = 0; i < curve.length; i++) {
            final float before = curve[i];
            step1(i, curve, curveLag, target[i], f);
            if (Math.abs(curve[i] - before) > CURVE_SETTLED) moved = true;
        }
        curveMoved = moved;
    }

    boolean curveMoved() {
        return curveMoved;
    }

    /**
     * The exact step of the pair of lags (lag' = (u - lag) / TAU, x' = (lag - x) / TAU) over a dt with u constant:
     * lag = u + (lag - u) e, x = u + (x - u) e + (lag - u) (dt / TAU) e.
     */
    private static void step(float[] x, float[] lag, float[] u, int n, float[] f) {
        for (int i = 0; i < n; i++) step1(i, x, lag, u[i], f);
    }

    private static void step1(int i, float[] x, float[] lag, float u, float[] f) {
        final float d = x[i] - u, dl = lag[i] - u;
        x[i] = u + d * f[0] + dl * f[1];
        lag[i] = u + dl * f[0];
    }

    private static double log2(float v) {
        return Math.log(Math.max(v, 1e-6f)) / Math.log(2.0);
    }
}
