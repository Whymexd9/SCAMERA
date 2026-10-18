package com.particlesdevs.photoncamera.capture;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * P212e (W3 = M7): the white balance of an OPPO native FULLRES session, per camera: a gated filter in log chroma (x = ln r / g,
 * ln b / g, one variance P) instead of P192's EMA of every 8th frame (k 0.3), which
 * <ul>
 * <li>was never reset: a session started from an older session's average (forgetNeutral ran in tests only) and ignored the fresh
 * neutral of the plain session just before it (OppoNativeFullRes.neutral preferred the stale own one);</li>
 * <li>took every frame: transition frames at unsettled AE (log31:200 at +704 ms, 'own AE from 30 ms ISO 133', 0.246 off the curve),
 * overexposed starts (warmer: 0.351 / 0.678 at p98 1.000 vs 0.332 / 0.670 settled) and night noise (0.456 / 0.594 -> 0.343 / 0.755
 * -> 0.447 / 0.614 within 22 s, log-2026-10-17 20:46:03-25);</li>
 * <li>never reached the photo, which estimated one frame afresh: a still filled with 0.364 / 0.665 was developed at 0.330 / 0.705
 * (log-2026-10-17 5701 / 6016), 4 of 22 shots more than 2 % off the viewfinder.</li>
 * </ul>
 * Now {@link #begin} resets the camera's state and seeds it from the plain session's ISP neutral when it is fresh (at most
 * {@link #FRESH_MS} old: P = {@link #P_FRESH} growing by {@link #P_AGE_PER_MS} with its age), else the stored one (OppoPlainState,
 * {@link #P_STORED}), else the held AWB gains of the first native result (not the 3745 K init signature, {@link #P_HELD}), else
 * the middle of the camera's learned curve ({@link #P_CURVE}). A measurement is taken only while the AE holds (nothing pending, no
 * rebuild in the last REBUILD_GAP frames), the frame is not dark ({@link #DARK_P50}) or clipped ({@link #CLIP_FRACTION} of the cells;
 * a p98 at {@link #CLIP_P98} while the AE has not settled) and enough cells count ({@link #MIN_CELLS}); its noise R grows in the
 * dark and with few cells. A Kalman step moves the state (innovations beyond {@link #GATE_SIGMA} are outliers; {@link #JUMP_OUTLIERS}
 * agreeing ones in a row are a new scene). An anchor ties the session to the plain neutral it started from: the first
 * {@link #ANCHOR_SAMPLES} measurements at settled AE give m0, and when the plain neutral was at most {@link #ANCHOR_FRESH_MS} old
 * and within {@link #ANCHOR_MAX} of m0, later measurements are moved by w (ln n_plain - ln m0), w fading with their distance from
 * m0 ({@link #ANCHOR_SPREAD}) and the anchor's age ({@link #ANCHOR_LIFE_MS}) - the estimator's steady bias against the ISP on the
 * same frame (probe bin12: ISP 0.408 / 0.614, shades of grey 0.426 / 0.535) no longer moves a scene that did not change.
 * <p>
 * Every completed ring frame and still records the state at its timestamp ({@link #record}); the photo takes the prior of its
 * reference frame and fuses its own estimate with it ({@link #fuse}; a photo that joins the outliers the viewfinder had begun to
 * see keeps its own estimate, as before - a new scene). A hand-set white balance (P201) overrides the state while it
 * is set ({@link #hand}). The state is written by the camera threads and read by the processing thread: everything is under the
 * class lock. scam_dev.txt "oppo_wb_filter 0": the P192 EMA as before; "oppo_wb_anchor 0": no anchor; "oppo_wb_fuse 0": the photo
 * takes its own estimate as before.
 */
public final class OppoNativeWb {
    private OppoNativeWb() {}

    /** Priors: fresh plain neutral age limit, its variance and growth; the stored, held-AWB and curve variances (ln units^2). */
    static final long FRESH_MS = 120_000L;
    static final double P_FRESH = 0.02 * 0.02, P_AGE_PER_MS = 0.05 * 0.05 / 600_000.0, P_STORED = 0.08 * 0.08,
            P_HELD = 0.12 * 0.12, P_CURVE = 0.15 * 0.15;
    /** Measurement noise: R_BASE at p50 0.01 and 10000 cells, within [R_MIN, R_MAX]; the process noise of an accepted step. */
    static final double R_BASE = 0.03 * 0.03, R_MIN = 0.02 * 0.02, R_MAX = 0.3 * 0.3, Q = 0.005 * 0.005;
    /** Gates: the meter's median, the clipped cell fraction, the p98 of an unsettled AE, the usable cells of the estimate. */
    static final float DARK_P50 = 0.004f, CLIP_FRACTION = 0.05f, CLIP_P98 = 0.99f;
    static final int MIN_CELLS = 2000;
    /** Frames between measurements (P192: every 8th). */
    static final int EVERY = 8;
    static final double GATE_SIGMA = 3.0;
    static final int JUMP_OUTLIERS = 3;
    /** The anchor: plain neutral age limit, largest offset, spread and life of its weight, samples, attempts before it is given up. */
    static final long ANCHOR_FRESH_MS = 10_000L;
    static final double ANCHOR_MAX = 0.15, ANCHOR_SPREAD = 0.06;
    static final long ANCHOR_LIFE_MS = 300_000L;
    static final int ANCHOR_SAMPLES = 3, ANCHOR_GIVE_UP = 40;
    /** The 3745 K init signature of AWBFrameControl (camera 2 2.225807 / 2.369458, camera 3 2.0466313 / 2.565555). */
    static final int INIT_CCT = 3745;
    /** Priors kept for the photo (ring frames and stills; a few per second). */
    static final int PRIORS = 512;

    /** A camera's state. */
    private static final class State {
        /** ln r / g, ln b / g; NaN: not seeded. */
        double lr = Double.NaN, lb = Double.NaN;
        /** The variance at {@code pMs} (elapsedRealtime); it grows by P_AGE_PER_MS until the next step. */
        double p;
        long pMs;
        String source = "none";
        /** The curve middle the first result falls back to when the held AWB gains are the init signature. */
        @Nullable float[] curve;
        boolean late;
        /** The anchor: pending (a fresh plain neutral to tie to), the plain neutral, m0 and the offset, when it was set. */
        boolean anchorPending;
        double plainR, plainB, m0r, m0b, dr, db;
        long anchorMs;
        int anchorTries;
        final double[][] held = new double[ANCHOR_SAMPLES][3];
        int heldCount;
        /** Outliers in a row and the last one. */
        int outliers;
        double olr, olb;
        /** Frames seen, the frame of the last measurement, the last meter median, steps taken, gated frames by reason. */
        int frames, measuredAt = Integer.MIN_VALUE / 2, steps;
        float p50 = -1f;
        int gatedAe, gatedDark, gatedClip, gatedCells;
        /** A hand-set neutral {r, 1, b} (null: automatic). */
        @Nullable float[] hand;

        double varianceAt(long nowMs) {
            return p + P_AGE_PER_MS * Math.max(0L, nowMs - pMs);
        }

        boolean seeded() {
            return !Double.isNaN(lr) && !Double.isNaN(lb);
        }

        void seed(float[] n, double variance, long nowMs, String how) {
            lr = Math.log(n[0] / n[1]);
            lb = Math.log(n[2] / n[1]);
            p = variance;
            pMs = nowMs;
            source = how;
        }
    }

    /** What the photo takes from the viewfinder at one frame: the state, its variance, the anchor then, the meter's median. */
    public static final class Prior {
        final String camera;
        final double lr, lb, p;
        final boolean anchored;
        final double m0r, m0b, dr, db;
        final long anchorAgeMs;
        final float p50;
        final boolean hand;
        /** The viewfinder's outliers in a row then and the last one (a new scene it had begun to see). */
        final int outliers;
        final double olr, olb;

        Prior(String camera, double lr, double lb, double p, boolean anchored, double m0r, double m0b, double dr, double db,
              long anchorAgeMs, float p50, boolean hand) {
            this(camera, lr, lb, p, anchored, m0r, m0b, dr, db, anchorAgeMs, p50, hand, 0, 0, 0);
        }

        Prior(String camera, double lr, double lb, double p, boolean anchored, double m0r, double m0b, double dr, double db,
              long anchorAgeMs, float p50, boolean hand, int outliers, double olr, double olb) {
            this.outliers = outliers;
            this.olr = olr;
            this.olb = olb;
            this.camera = camera;
            this.lr = lr;
            this.lb = lb;
            this.p = p;
            this.anchored = anchored;
            this.m0r = m0r;
            this.m0b = m0b;
            this.dr = dr;
            this.db = db;
            this.anchorAgeMs = anchorAgeMs;
            this.p50 = p50;
            this.hand = hand;
        }
    }

    /** The photo's neutral and how it was found (log line). */
    public static final class Fused {
        public final float[] neutral;
        public final String how;

        Fused(float[] neutral, String how) {
            this.neutral = neutral;
            this.how = how;
        }
    }

    private static final Map<String, State> STATES = new HashMap<>();
    private static final LinkedHashMap<Long, Prior> RECORDED = new LinkedHashMap<Long, Prior>(64, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Prior> eldest) {
            return size() > PRIORS;
        }
    };

    /** scam_dev.txt "oppo_wb_filter" (default on; off: the P192 EMA, OppoNativeFullRes.measuredNeutral). */
    public static boolean on() {
        return PreferenceKeys.scamDevSwitch("oppo_wb_filter", true);
    }

    static boolean anchorOn() {
        return PreferenceKeys.scamDevSwitch("oppo_wb_anchor", true);
    }

    /** scam_dev.txt "oppo_wb_fuse" (default on; off: the photo's own estimate as before). */
    public static boolean fuseOn() {
        return on() && PreferenceKeys.scamDevSwitch("oppo_wb_fuse", true);
    }

    /**
     * A native session of the camera starts (elapsedRealtime {@code nowMs}): its state is reset and seeded from the plain neutral
     * {r, 1, b} {@code plainAgeMs} old (negative: none), else the stored one, else (at the first result, {@link #lateSeed}) the held
     * AWB gains or the curve middle. Returns the log line.
     */
    static synchronized String begin(String camera, long nowMs, @Nullable float[] plain, long plainAgeMs, @Nullable float[] stored,
                                     @Nullable float[] curveMiddle) {
        if (camera == null) return "no camera";
        final State s = new State();
        STATES.put(camera, s);
        s.curve = valid(curveMiddle) ? curveMiddle.clone() : null;
        if (valid(plain) && plainAgeMs >= 0 && plainAgeMs <= FRESH_MS) {
            s.seed(plain, P_FRESH + P_AGE_PER_MS * plainAgeMs, nowMs,
                    String.format(Locale.ROOT, "the plain neutral %.1f s old", plainAgeMs / 1000.0));
            if (plainAgeMs <= ANCHOR_FRESH_MS && anchorOn()) {
                s.anchorPending = true;
                s.plainR = s.lr;
                s.plainB = s.lb;
            }
        } else if (valid(stored)) {
            s.seed(stored, P_STORED, nowMs, "the stored plain neutral");
        } else {
            s.late = true;
        }
        return s.seeded() ? String.format(Locale.ROOT, "white balance from %s %.3f / %.3f (sd %.3f)%s", s.source, Math.exp(s.lr),
                Math.exp(s.lb), Math.sqrt(s.p), s.anchorPending ? ", anchor pending" : "")
                : "white balance: no plain neutral (the first result's held AWB gains or the curve middle)";
    }

    /**
     * The first native result of a session without a plain neutral: the held AWB gains {R, G, B} with their CCT (null: none; the
     * 3745 K init signature is refused) seed it with P_HELD, else the curve middle with P_CURVE. Returns how (null: nothing).
     */
    @Nullable
    static synchronized String lateSeed(String camera, @Nullable float[] awbGains, int awbCct, long nowMs) {
        final State s = camera == null ? null : STATES.get(camera);
        if (s == null || !s.late || s.seeded()) return null;
        s.late = false;
        final float[] held = heldNeutral(awbGains, awbCct);
        if (held != null) s.seed(held, P_HELD, nowMs, "the held AWB gains (" + awbCct + " K)");
        else if (s.curve != null) s.seed(s.curve, P_CURVE, nowMs, "the curve middle");
        else return null;
        return String.format(Locale.ROOT, "white balance from %s %.3f / %.3f (sd %.3f)", s.source, Math.exp(s.lr), Math.exp(s.lb), Math.sqrt(s.p));
    }

    /** The neutral {G / R, 1, G / B} of held AWB gains, null for none, a bad value or the 3745 K init signature (any camera). */
    @Nullable
    static float[] heldNeutral(@Nullable float[] gains, int cct) {
        if (gains == null || gains.length < 3 || cct == INIT_CCT || cct <= 0) return null;
        final float g = gains[1] > 0f ? gains[1] : 1f;
        if (!(gains[0] > 0f) || !(gains[2] > 0f) || Float.isInfinite(gains[0]) || Float.isInfinite(gains[2])) return null;
        final float[] n = {g / gains[0], 1f, g / gains[2]};
        return valid(n) ? n : null;
    }

    /** P201: a hand-set neutral {r, 1, b} holds while it is set (null: automatic again; the state goes on from where it was). */
    static synchronized void hand(String camera, @Nullable float[] neutral) {
        final State s = camera == null ? null : STATES.get(camera);
        if (s == null) {
            if (camera == null || !valid(neutral)) return;
            final State fresh = new State();
            fresh.hand = neutral.clone();
            STATES.put(camera, fresh);
            return;
        }
        s.hand = valid(neutral) ? neutral.clone() : null;
    }

    /** The camera's neutral {r, 1, b} now (the hand-set one first); null: not seeded and not measured yet. */
    @Nullable
    static synchronized float[] neutral(String camera) {
        final State s = camera == null ? null : STATES.get(camera);
        if (s == null) return null;
        if (s.hand != null) return s.hand.clone();
        return s.seeded() ? new float[]{(float) Math.exp(s.lr), 1f, (float) Math.exp(s.lb)} : null;
    }

    /**
     * Why a metered frame's neutral is not measured (null: it is): the AE moving (a change pending or a rebuild in its last
     * REBUILD_GAP frames), a dark frame, a clipped one (more than CLIP_FRACTION of the cells with a clipped site; p98 at CLIP_P98
     * while the AE has not settled - a settled frame with a few clipped highlights still counts: its clip bias is the anchor's).
     * {@code p50} / {@code p98} / {@code clip} negative: not known.
     */
    @Nullable
    static String gate(boolean pending, int sinceRebuild, boolean settled, float p50, float p98, float clip) {
        if (pending || sinceRebuild < NativeRawExposure.REBUILD_GAP) return "AE";
        if (p50 >= 0f && p50 < DARK_P50) return "dark";
        if (clip > CLIP_FRACTION || p98 >= CLIP_P98 && !settled) return "clipped";
        return null;
    }

    /**
     * One metered native frame of the camera: true when its neutral is to be measured now ({@link #EVERY} frames after the last
     * measurement and not gated, {@link #gate}); the meter's median is kept for the noise of the measurement and the photo.
     */
    static synchronized boolean wants(String camera, boolean pending, int sinceRebuild, boolean settled, float p50, float p98, float clip) {
        final State s = camera == null ? null : STATES.get(camera);
        if (s == null) return false;
        s.frames++;
        if (p50 >= 0f) s.p50 = p50;
        if (s.frames - s.measuredAt < EVERY) return false;
        final String why = gate(pending, sinceRebuild, settled, p50, p98, clip);
        if (why == null) return true;
        switch (why) {
            case "AE": s.gatedAe++; break;
            case "dark": s.gatedDark++; break;
            default: s.gatedClip++; break;
        }
        return false;
    }

    /** The noise (ln units^2) of an estimate of {@code cells} cells at the meter's median {@code p50} (negative: not known). */
    static double noise(float p50, int cells) {
        final double level = p50 >= 0f ? Math.max(p50, 0.002f) : 0.01;
        final double r = R_BASE * (0.01 / level) * (10000.0 / Math.max(cells, MIN_CELLS));
        return Math.max(R_MIN, Math.min(R_MAX, r));
    }

    /**
     * A measured neutral {r, 1, b} of {@code cells} cells (null: too few) of the frame {@link #wants} asked for, at AE
     * {@code settled}: the anchor's sample, or a step of the filter. Returns a log line now and then (null: none due).
     */
    @Nullable
    static synchronized String measure(String camera, @Nullable float[] neutral, int cells, boolean settled, long nowMs) {
        final State s = camera == null ? null : STATES.get(camera);
        if (s == null) return null;
        s.measuredAt = s.frames;
        if (!valid(neutral) || cells < MIN_CELLS) {
            s.gatedCells++;
            return null;
        }
        final double zr = Math.log(neutral[0] / neutral[1]), zb = Math.log(neutral[2] / neutral[1]);
        final double r = noise(s.p50, cells);
        if (s.anchorPending) {
            if (!settled) {
                if (++s.anchorTries < ANCHOR_GIVE_UP) return null; // the plain neutral holds until the AE settles
                s.anchorPending = false;
                return "; anchor given up (the AE did not settle)" + step(s, zr, zb, r, nowMs);
            }
            s.held[s.heldCount][0] = zr;
            s.held[s.heldCount][1] = zb;
            s.held[s.heldCount][2] = r;
            if (++s.heldCount < ANCHOR_SAMPLES) return null;
            s.anchorPending = false;
            s.m0r = median(s.held, 0);
            s.m0b = median(s.held, 1);
            final double dr = s.plainR - s.m0r, db = s.plainB - s.m0b;
            final String anchor;
            if (Math.hypot(dr, db) <= ANCHOR_MAX) {
                s.dr = dr;
                s.db = db;
                s.anchorMs = nowMs;
                anchor = String.format(Locale.ROOT, "; anchor: plain %.3f / %.3f, m0 %.3f / %.3f, offset %+.3f / %+.3f ln", Math.exp(s.plainR),
                        Math.exp(s.plainB), Math.exp(s.m0r), Math.exp(s.m0b), dr, db);
            } else {
                anchor = String.format(Locale.ROOT, "; no anchor: m0 %.3f / %.3f is %.3f ln from the plain neutral", Math.exp(s.m0r),
                        Math.exp(s.m0b), Math.hypot(dr, db));
            }
            String steps = "";
            for (int i = 0; i < s.heldCount; i++) steps = step(s, s.held[i][0], s.held[i][1], s.held[i][2], nowMs);
            return anchor + steps;
        }
        final String how = step(s, zr, zb, r, nowMs);
        return s.steps <= 1 || s.steps % 16 == 0 || how.contains("jump") ? how : null;
    }

    /** One Kalman step of a measurement (ln r, ln b) with noise r (mapped by the anchor first). Returns the log tail. */
    private static String step(State s, double zr, double zb, double r, long nowMs) {
        double mr = zr, mb = zb, w = 0;
        if (s.anchorMs > 0) {
            w = anchorWeight(zr, zb, s.m0r, s.m0b, nowMs - s.anchorMs);
            mr += w * s.dr;
            mb += w * s.db;
        }
        String result;
        if (!s.seeded()) {
            s.lr = mr;
            s.lb = mb;
            s.p = r;
            s.pMs = nowMs;
            s.source = "the first measurement";
            s.steps++;
            result = "first";
        } else {
            final double p = s.varianceAt(nowMs);
            final double d = Math.hypot(mr - s.lr, mb - s.lb), sigma = Math.sqrt(p + r);
            if (d > GATE_SIGMA * sigma) {
                final boolean agrees = s.outliers > 0 && Math.hypot(mr - s.olr, mb - s.olb) <= GATE_SIGMA * Math.sqrt(2 * r);
                s.outliers = agrees ? s.outliers + 1 : 1;
                s.olr = mr;
                s.olb = mb;
                if (s.outliers >= JUMP_OUTLIERS) {
                    s.lr = mr;
                    s.lb = mb;
                    s.p = r;
                    s.pMs = nowMs;
                    s.outliers = 0;
                    s.steps++;
                    result = "jump (a new scene)";
                } else {
                    result = String.format(Locale.ROOT, "outlier %d (%.3f ln, %.1f sd)", s.outliers, d, d / sigma);
                }
            } else {
                s.outliers = 0;
                final double k = p / (p + r);
                s.lr += k * (mr - s.lr);
                s.lb += k * (mb - s.lb);
                s.p = (1 - k) * p + Q;
                s.pMs = nowMs;
                s.steps++;
                result = String.format(Locale.ROOT, "k %.2f", k);
            }
        }
        final String gated = s.gatedAe + s.gatedDark + s.gatedClip + s.gatedCells == 0 ? ""
                : String.format(Locale.ROOT, ", skipped AE %d dark %d clipped %d cells %d", s.gatedAe, s.gatedDark, s.gatedClip, s.gatedCells);
        return String.format(Locale.ROOT, "; filter %.3f / %.3f%s %s -> %.3f / %.3f sd %.3f%s", Math.exp(zr), Math.exp(zb),
                w > 0 ? String.format(Locale.ROOT, " (anchor w %.2f: %.3f / %.3f)", w, Math.exp(mr), Math.exp(mb)) : "", result,
                Math.exp(s.lr), Math.exp(s.lb), Math.sqrt(s.p), gated);
    }

    /** The anchor's weight for a measurement: exp(-distance from m0 / ANCHOR_SPREAD) exp(-age / ANCHOR_LIFE_MS). */
    static double anchorWeight(double zr, double zb, double m0r, double m0b, long ageMs) {
        return Math.exp(-Math.hypot(zr - m0r, zb - m0b) / ANCHOR_SPREAD) * Math.exp(-Math.max(0L, ageMs) / (double) ANCHOR_LIFE_MS);
    }

    private static double median(double[][] v, int column) {
        final double a = v[0][column], b = v[1][column], c = v[2][column];
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    /** A completed ring frame or still of the camera at sensor {@code timestamp}: the state now is its prior for the photo. */
    static synchronized void record(String camera, @Nullable Long timestamp, long nowMs) {
        final State s = camera == null || timestamp == null ? null : STATES.get(camera);
        if (s == null) return;
        if (s.hand != null) {
            RECORDED.put(timestamp, new Prior(camera, Math.log(s.hand[0] / s.hand[1]), Math.log(s.hand[2] / s.hand[1]), 1e-8, false, 0, 0,
                    0, 0, 0, s.p50, true));
            return;
        }
        if (!s.seeded()) return;
        final boolean anchored = s.anchorMs > 0;
        RECORDED.put(timestamp, new Prior(camera, s.lr, s.lb, s.varianceAt(nowMs), anchored, s.m0r, s.m0b, s.dr, s.db,
                anchored ? nowMs - s.anchorMs : 0, s.p50, false, s.outliers, s.olr, s.olb));
    }

    /** The prior recorded for a frame's sensor timestamp (null: none - not an OPPO native frame, or long gone). */
    @Nullable
    public static synchronized Prior prior(long timestamp) {
        return RECORDED.get(timestamp);
    }

    /**
     * The photo's neutral: its own estimate {r, 1, b} of {@code cells} cells (mapped by the prior's anchor) fused with the
     * viewfinder's prior - a Kalman step, so the result lies between the two, nearer the one with the smaller variance; more than
     * GATE_SIGMA off the prior and with the outliers the viewfinder had begun to see it is the photo's own (mapped) estimate (a
     * new scene). Null when either is unusable (the caller keeps its own).
     */
    @Nullable
    public static Fused fuse(@Nullable Prior prior, @Nullable float[] own, int cells) {
        if (prior == null || !valid(own) || Double.isNaN(prior.lr) || Double.isNaN(prior.lb) || !(prior.p >= 0)) return null;
        final double zr = Math.log(own[0] / own[1]), zb = Math.log(own[2] / own[1]);
        double mr = zr, mb = zb, w = 0;
        if (prior.anchored) {
            w = anchorWeight(zr, zb, prior.m0r, prior.m0b, prior.anchorAgeMs);
            mr += w * prior.dr;
            mb += w * prior.db;
        }
        final double r = noise(prior.p50, cells);
        // a new scene the viewfinder had begun to see (its outliers then, which the photo joins) before its three outliers jumped:
        // the photo's own estimate, as before. Else the Kalman step - a single-frame estimate off a steady viewfinder (the curve
        // projection snaps to its points, 0.029 ln median off the curve) is the less certain one. A hand-set prior always holds.
        final double sd = Math.hypot(mr - prior.lr, mb - prior.lb) / Math.sqrt(prior.p + r);
        final boolean newScene = !prior.hand && sd > GATE_SIGMA && prior.outliers > 0
                && Math.hypot(mr - prior.olr, mb - prior.olb) <= GATE_SIGMA * Math.sqrt(2 * r);
        final double k = newScene ? 1.0 : prior.p / (prior.p + r);
        final double fr = prior.lr + k * (mr - prior.lr), fb = prior.lb + k * (mb - prior.lb);
        final float[] n = {(float) Math.exp(fr), 1f, (float) Math.exp(fb)};
        if (!valid(n)) return null;
        return new Fused(n, String.format(Locale.ROOT, "prior %.3f / %.3f (sd %.3f%s) / photo %.3f / %.3f%s (sd %.3f, %.1f sd off) / %s %.3f / %.3f (k %.2f)",
                Math.exp(prior.lr), Math.exp(prior.lb), Math.sqrt(prior.p), prior.hand ? ", hand-set" : "", own[0] / own[1], own[2] / own[1],
                w > 0 ? String.format(Locale.ROOT, " -> anchored %.3f / %.3f", Math.exp(mr), Math.exp(mb)) : "", Math.sqrt(r), sd,
                newScene ? "a new scene (the viewfinder's outliers agree), its own" : "fused", n[0], n[2], k));
    }

    static boolean valid(@Nullable float[] n) {
        if (n == null || n.length < 3 || !(n[1] > 0f) || Float.isInfinite(n[1])) return false;
        final float r = n[0] / n[1], b = n[2] / n[1];
        return r > 0.05f && r < 20f && b > 0.05f && b < 20f;
    }

    /** Tests: no state, no priors. */
    static synchronized void resetForTest() {
        STATES.clear();
        RECORDED.clear();
    }
}
