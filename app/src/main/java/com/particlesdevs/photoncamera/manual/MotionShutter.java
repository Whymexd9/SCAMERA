package com.particlesdevs.photoncamera.manual;

/**
 * P110 The shutter ceiling of the «Движение» mode (owner, 2026-10-13: «он не отличается в плане захвата движения от
 * обычного»). The mode's fixed AE range [30, 30] only kept the ring frames at most 1/30 s, which the photo mode's AE already
 * used indoors and at night (X500 Pro Max log 2026-10-13: every ring frame 30 ms at ISO 35000 in both modes), so a moving
 * subject smeared the same.
 * <p>
 * The camera's AE runs as before until its settled shutter is longer than the ceiling (1/250 s by default; under 50 / 60 Hz
 * lamps whole half periods of the mains, so the frame has no stripes). Then the controller takes the AE's exposure (ISO ×
 * shutter) over with AE off: the shortest shutter the highest ISO allows, not shorter than the ceiling, not longer than one
 * ring frame. From then on it keeps the RAW frames' mean level at what the AE's settled frames had. Each level is paired with
 * the exposure of its own frame (by sensor timestamp), so the frames still in flight from an older request say the same about
 * the scene and nothing winds up. Back to the AE once the scene is bright enough for a short shutter of its own. Pure
 * arithmetic here, tested on the host; ParamController applies it.
 */
public final class MotionShutter {
    /** The AE's settled shutter must pass the ceiling by this much for a few frames before the ceiling takes over. */
    static final double ENGAGE_RATIO = 1.15;
    static final int ENGAGE_FRAMES = 3, RELEASE_LEVELS = 4, FLICKER_FRAMES = 6;
    /** An AE without a settled state: this many frames within 5 % of the exposure before it counts as settled. */
    static final int STABLE_FRAMES = 8;
    static final double STABLE_RATIO = 0.05;
    /** No RAW level for this long under the ceiling: back to the AE. */
    static final long NO_LEVEL_NS = 2_000_000_000L;
    /** No correction below a sixth of a stop; two thirds of the error, at most two stops a level. */
    static final double DEADBAND_STOPS = 1.0 / 6, DAMPING = 2.0 / 3, MAX_STEP_STOPS = 2;
    /** Weight of a new AE frame in the learned level; levels above this are clipped scenes and teach nothing. */
    static final double TARGET_EMA = 0.3, LEVEL_MAX = 0.98;
    /** STATISTICS_SCENE_FLICKER_50HZ / _60HZ and the half periods that do not band. */
    static final int FLICKER_50HZ = 1, FLICKER_60HZ = 2;
    static final long HALF_PERIOD_50HZ_NS = 10_000_000L, HALF_PERIOD_60HZ_NS = 8_333_333L;
    /** CONTROL_AE_STATE_CONVERGED / _LOCKED / _FLASH_REQUIRED. */
    static final int AE_CONVERGED = 2, AE_LOCKED = 3, AE_FLASH_REQUIRED = 4;
    /** Results kept for pairing a RAW level with its frame (about 0.8 s at 30 fps). */
    static final int RECORDS = 24;
    /**
     * P111 Lamp flicker the AE's anti-banding shutters only suggest is checked on the frames themselves (the stock vivo snapshot
     * mode has a flicker sensor for it): row bands of more than this (half peak to peak, against a band-free frame of the AE)
     * on two short frames latch it; six frames without them clear it for the session; no verdict within 1.5 s latches it.
     */
    static final double BAND_MAX = 0.04;
    static final int BAND_HITS = 2, BAND_MISSES = 6;
    static final long BAND_WAIT_NS = 1_500_000_000L;
    /**
     * P124 Stops below the scene's exposure the ceiling takes at the top ISO while the scene moves, before its shutter grows
     * (OPPO Find X7 Ultra log 2026-10-13 22:26, a waving hand under 100 Hz lamps: ceiling 8.3 ms, the top ISO short of the
     * scene, the shutter went 16.7 -> 25 -> 33 ms and every lamp pulse left a copy of the hand in the frame). The tone stage
     * brightens the photo (LinearExposure, up to 128x); the merge and the denoise take the noise.
     */
    public static final double MOTION_UNDER_STOPS = 2;

    private MotionShutter() {}

    /** The setting's ceiling: 1/{@code denominator} s; 0 or less: no ceiling (returns 0). */
    public static long settingNs(float denominator) {
        return denominator > 0 ? Math.round(1e9 / denominator) : 0;
    }

    /** {@code ns} rounded down to whole half periods, at least one (none: unchanged). */
    static long quantizeDown(long ns, long halfNs) {
        return halfNs <= 0 ? ns : Math.max(halfNs, ns / halfNs * halfNs);
    }

    /** {@code ns} rounded up to whole half periods (none: unchanged), but not beyond {@code maxNs} rounded down to them. */
    static long quantize(long ns, long halfNs, long maxNs) {
        if (halfNs <= 0) return Math.min(ns, maxNs);
        long q = (ns + halfNs - 1) / halfNs * halfNs;
        long top = Math.max(halfNs, maxNs / halfNs * halfNs);
        return Math.min(q, top);
    }

    /** One look of the controller: what the request needs. */
    public static final int NONE = 0, APPLY = 1, RELEASE = 2;

    /**
     * P111 The ceiling by how fast the scene moves (capture/MotionMeter), as the stock vivo snapshot mode's motion levels pick
     * its exposure: the shutter that keeps the smear within {@link #BLUR_PX} RAW px, in half stops below the setting (at most
     * {@link #MAX_LEVEL} half stops, not below 1/4000 s). A faster level at once; back one half stop per {@link #HOLD_NS} of
     * slower motion (a subject that stops for a moment keeps the short shutter); no measurement for a second counts as still.
     */
    public static final class Levels {
        static final double BLUR_PX = 2.0;
        static final int MAX_LEVEL = 6;
        static final long HOLD_NS = 600_000_000L, STALE_NS = 1_000_000_000L, MIN_CAP_NS = 250_000L;
        private int level;
        private long changedAtNs;

        public int level() { return level; }

        public void reset() {
            level = 0;
            changedAtNs = 0;
        }

        /** Half stops below the setting the motion needs ({@code pxPerS} RAW px / s, NaN or <= 0: still). */
        static int wanted(long settingNs, double pxPerS) {
            if (settingNs <= 0 || !(pxPerS > 0)) return 0;
            final double needNs = BLUR_PX / pxPerS * 1e9;
            final double halfStops = 2 * Math.log(settingNs / needNs) / Math.log(2);
            return (int) Math.max(0, Math.min(MAX_LEVEL, Math.ceil(halfStops - 0.25)));
        }

        /** The ceiling for {@code settingNs} (0: none) after a measurement of {@code pxPerS} taken at {@code sampleAtNs}. */
        public long capNs(long settingNs, double pxPerS, long sampleAtNs, long nowNs) {
            if (settingNs <= 0) {
                reset();
                return 0;
            }
            final int want = nowNs - sampleAtNs > STALE_NS ? 0 : wanted(settingNs, pxPerS);
            if (want > level) {
                level = want;
                changedAtNs = nowNs;
            } else if (want < level && nowNs - changedAtNs >= HOLD_NS) {
                level--;
                changedAtNs = nowNs;
            }
            return Math.max(Math.min(settingNs, MIN_CAP_NS), Math.round(settingNs / Math.pow(2, level / 2.0)));
        }
    }

    /** What the request may use: the setting, one ring frame, the ISO range (top = what the HAL honours), EV, flicker. */
    public static final class Limits {
        long settingNs, maxNs;
        int isoMin, isoMax;
        boolean flickerSafe, motionPriority;
        double evStops, underStops;

        public Limits set(long settingNs, long maxNs, int isoMin, int isoMax, boolean flickerSafe, double evStops) {
            this.settingNs = settingNs;
            this.maxNs = maxNs;
            this.isoMin = isoMin;
            this.isoMax = isoMax;
            this.flickerSafe = flickerSafe;
            this.evStops = evStops;
            this.underStops = 0;
            this.motionPriority = false;
            return this;
        }

        public Limits motionPriority(boolean value) {
            motionPriority = value;
            return this;
        }

        /** P124: the scene moves: up to {@code stops} under its exposure at the top ISO before the shutter passes the ceiling. */
        public Limits under(double stops) {
            this.underStops = Math.max(0, stops);
            return this;
        }
    }

    /** The ceiling's state for one camera session. */
    public static final class Controller {
        private boolean active;
        private double product, target0 = Double.NaN; // ISO·s in force; the AE's RAW level at EV 0
        private long shutterNs;
        private int iso;
        private int over, under, stable;
        private double lastAeProduct;
        private long flickerHalfNs, suspectHalfNs, engagedAtNs, usedProfileTs;
        private int votes50, votes60, bandHits, bandMisses;
        private boolean bandFree;
        private float[] refProfile;
        private double bands = Double.NaN;
        private long usedLevelTs, lastLevelAtNs;
        private final long[] recTs = new long[RECORDS], recNs = new long[RECORDS];
        private final int[] recIso = new int[RECORDS];
        private final boolean[] recAeOff = new boolean[RECORDS], recSettled = new boolean[RECORDS];
        private int recNext;

        public boolean active() { return active; }
        public long shutterNs() { return shutterNs; }
        public int iso() { return iso; }
        public long flickerHalfNs() { return flickerHalfNs; }
        /** The half period the AE's shutters suggest, not yet seen on the frames (0: none). */
        public long suspectHalfNs() { return suspectHalfNs; }
        /** Short frames showed no row bands: the suggested flicker is cleared for the session. */
        public boolean bandFree() { return bandFree; }
        public double bands() { return bands; }
        public double target() { return target0; }

        /**
         * P124: how far the request in force stays under the scene's exposure (1: not at all; the top ISO short of the scene or
         * the moving scene's {@link Limits#under}): the viewfinder's post-RAW gain makes it up, the RAW frames keep it.
         */
        public double underRatio() {
            final double inForce = (double) iso * shutterNs / 1e9;
            return active && inForce > 0 && product > 0 ? Math.max(1, product / inForce) : 1;
        }

        /** A new session (camera, lens, restart): the AE's frames teach it again. */
        public void reset() {
            drop();
            votes50 = votes60 = stable = bandHits = bandMisses = 0;
            lastAeProduct = 0;
            flickerHalfNs = suspectHalfNs = 0;
            bandFree = false;
            refProfile = null;
            bands = Double.NaN;
            usedLevelTs = lastLevelAtNs = usedProfileTs = 0;
            java.util.Arrays.fill(recTs, 0);
        }

        /**
         * Hand-set exposure, the mode off, a release or a tap that meters a new point: the ceiling lets go without a request of
         * its own and learns the AE's level again before it takes over.
         */
        public void drop() {
            active = false;
            over = under = 0;
            target0 = Double.NaN;
        }

        /** The ceiling in force: the setting, at least the half period of the latched flicker. */
        public long capNs(long settingNs) {
            return settingNs <= 0 ? 0 : Math.max(settingNs, flickerHalfNs);
        }

        /**
         * One viewfinder result (sensor {@code timestampNs}, exposure {@code ns} at {@code iso}, AE off or on with
         * {@code aeState}, null when the HAL reports none) and the newest RAW level ({@code levelTs} its frame's sensor
         * timestamp, {@code level} 0..1, NaN when none). Returns NONE, APPLY (the request needs {@link #shutterNs()} /
         * {@link #iso()}; on the first one the ceiling took over) or RELEASE (back to the AE).
         */
        public int onResult(long timestampNs, long ns, int iso, boolean aeOff, Integer aeState, Integer sceneFlicker,
                            long levelTs, double level, Limits l, long nowNs) {
            return onResult(timestampNs, ns, iso, aeOff, aeState, sceneFlicker, levelTs, level, 0, null, l, nowNs);
        }

        /** The same with the newest row profile of a ring frame ({@code profileTs} its sensor timestamp; null: none). */
        public int onResult(long timestampNs, long ns, int iso, boolean aeOff, Integer aeState, Integer sceneFlicker,
                            long levelTs, double level, long profileTs, float[] profile, Limits l, long nowNs) {
            final boolean settled = !aeOff && settled(aeState, ns, iso);
            record(timestampNs, ns, iso, aeOff, settled);
            if (!l.flickerSafe) {
                flickerHalfNs = suspectHalfNs = 0;
                votes50 = votes60 = bandHits = bandMisses = 0;
            } else {
                // the AE's own shutters vote, not the frame's longest one (33.3 ms at 30 fps is 4 x 8.33 ms by chance)
                latchFlicker(ns, sceneFlicker, !aeOff && ns < l.maxNs * 0.97);
                checkBands(profileTs, profile, nowNs);
            }
            int frame = -1;
            if (levelTs != 0 && levelTs != usedLevelTs && level > 0 && level <= 1) {
                frame = find(levelTs);
                if (frame >= 0) {
                    usedLevelTs = levelTs;
                    lastLevelAtNs = nowNs;
                }
            }
            return active ? hold(frame, level, l, nowNs) : auto(ns, iso, aeOff, settled, frame, level, l, nowNs);
        }

        private int auto(long ns, int aeIso, boolean aeOff, boolean settled, int frame, double level, Limits l, long nowNs) {
            if (aeOff) { // not a frame of the camera AE (hand-set, a shot's restore frame)
                over = 0;
                return NONE;
            }
            if (frame >= 0 && !recAeOff[frame] && recSettled[frame] && level <= LEVEL_MAX) {
                final double at0 = level / Math.pow(2, l.evStops);
                target0 = Double.isNaN(target0) ? at0 : target0 + TARGET_EMA * (at0 - target0);
            }
            final long cap = l.motionPriority ? l.settingNs : capNs(l.settingNs);
            if (cap <= 0 || ns <= 0 || aeIso <= 0 || Double.isNaN(target0) || !settled) {
                over = 0;
                return NONE;
            }
            // under lamps the flicker vote decides first (then the frames themselves), so the ceiling starts knowing it
            final int need = l.flickerSafe && flickerHalfNs == 0 && suspectHalfNs == 0 && !bandFree && (votes50 > 0 || votes60 > 0)
                    ? FLICKER_FRAMES : ENGAGE_FRAMES;
            over = ns > cap * ENGAGE_RATIO ? over + 1 : 0;
            if (over < need) return NONE;
            over = under = 0;
            active = true;
            product = (double) aeIso * ns / 1e9;
            lastLevelAtNs = engagedAtNs = nowNs;
            choose(cap, l);
            return APPLY;
        }

        private int hold(int frame, double level, Limits l, long nowNs) {
            // a suggested flicker no frame has decided on in time: latched (no stripes rather than a guess)
            if (suspectHalfNs > 0 && flickerHalfNs == 0 && nowNs - engagedAtNs > BAND_WAIT_NS) latchSuspect();
            final long cap = l.motionPriority ? l.settingNs : capNs(l.settingNs);
            if (cap <= 0) {
                drop();
                return RELEASE;
            }
            if (frame < 0) {
                // no level for too long (the payload is no longer plain, the reader stalled): the AE again
                if (nowNs - lastLevelAtNs > NO_LEVEL_NS) {
                    drop();
                    return RELEASE;
                }
                return rechoose(cap, l); // the reachable ISO or the flicker may have changed
            }
            final double frameProduct = (double) recIso[frame] * recNs[frame] / 1e9;
            if (frameProduct <= 0 || Double.isNaN(target0)) return NONE;
            // what this frame says the scene needs, against what is in force: the same from any frame, old request or new
            final double wanted = frameProduct * target0 * Math.pow(2, l.evStops) / Math.min(level, LEVEL_MAX);
            double stops = Math.log(wanted / product) / Math.log(2);
            // a clipped frame only says the scene is at least this bright: it may lower the exposure, never raise it
            if (level > LEVEL_MAX && stops > 0) stops = 0;
            if (Math.abs(stops) >= DEADBAND_STOPS)
                product *= Math.pow(2, Math.max(-MAX_STEP_STOPS, Math.min(MAX_STEP_STOPS, stops * DAMPING)));
            // no further than the request can follow (no windup in the dark or against a covered lens)
            final double floor = (double) l.isoMin * cap / 1e9;
            final double ceiling = (double) Math.max(l.isoMin, l.isoMax) * Math.max(cap, l.maxNs) / 1e9;
            product = Math.max(floor / 2, Math.min(ceiling, product));
            // bright enough for the AE's own short shutter at its lowest ISO: back to the AE
            under = product < floor ? under + 1 : 0;
            if (under >= RELEASE_LEVELS) {
                drop();
                return RELEASE;
            }
            return rechoose(cap, l);
        }

        private int rechoose(long cap, Limits l) {
            final long oldShutter = shutterNs;
            final int oldIso = iso;
            choose(cap, l);
            return shutterNs == oldShutter && iso == oldIso ? NONE : APPLY;
        }

        /**
         * Shortest shutter the top ISO allows (not below the ceiling, not beyond one frame), then the ISO for it. While the scene
         * moves (P124) the top ISO may stay up to {@link Limits#under} stops short of the scene first, and lamp shutters round
         * down to whole half periods: one lamp pulse per frame, not a copy of the subject for each.
         */
        private void choose(long cap, Limits l) {
            final int top = Math.max(l.isoMin, l.isoMax);
            final long frame = Math.max(cap, l.maxNs);
            long s = Math.round(product / top / Math.pow(2, l.underStops) * 1e9);
            s = l.motionPriority ? cap : Math.max(cap, Math.min(frame, s));
            // Keep measuring bands, but a mains latch must not lengthen an action exposure.
            final long period = l.motionPriority && cap < flickerHalfNs ? 0 : flickerHalfNs;
            s = l.motionPriority || l.underStops > 0 ? quantizeDown(s, period) : quantize(s, period, frame);
            shutterNs = Math.max(1, s);
            iso = (int) Math.max(l.isoMin, Math.min(top, Math.round(product / (shutterNs / 1e9))));
        }

        /** The AE has settled: its state says so, or (no state, or one that never settles) the exposure holds still. */
        private boolean settled(Integer aeState, long ns, int aeIso) {
            final double p = (double) ns * aeIso;
            stable = lastAeProduct > 0 && Math.abs(p / lastAeProduct - 1) <= STABLE_RATIO ? stable + 1 : 0;
            lastAeProduct = p;
            if (aeState != null && (aeState == AE_CONVERGED || aeState == AE_LOCKED || aeState == AE_FLASH_REQUIRED)) return true;
            return stable >= STABLE_FRAMES;
        }

        private void record(long ts, long ns, int iso, boolean aeOff, boolean settled) {
            if (ts == 0) return;
            final int i = recNext;
            recNext = (recNext + 1) % RECORDS;
            recTs[i] = ts;
            recNs[i] = ns;
            recIso[i] = iso;
            recAeOff[i] = aeOff;
            recSettled[i] = settled;
        }

        private int find(long ts) {
            for (int i = 0; i < RECORDS; i++) if (recTs[i] == ts) return i;
            return -1;
        }

        /**
         * The mains flicker: reported by the HAL (latched at once), or suggested by the AE's anti-banding shutters (whole
         * multiples of 10 / 8.33 ms; the anti-banding setting alone gives them too, so the frames decide: checkBands).
         */
        private void latchFlicker(long aeNs, Integer sceneFlicker, boolean vote) {
            if (flickerHalfNs > 0) return;
            if (sceneFlicker != null && sceneFlicker == FLICKER_50HZ) { flickerHalfNs = HALF_PERIOD_50HZ_NS; return; }
            if (sceneFlicker != null && sceneFlicker == FLICKER_60HZ) { flickerHalfNs = HALF_PERIOD_60HZ_NS; return; }
            if (!vote || bandFree || suspectHalfNs > 0) return;
            votes50 = multiple(aeNs, HALF_PERIOD_50HZ_NS) ? votes50 + 1 : 0;
            votes60 = multiple(aeNs, HALF_PERIOD_60HZ_NS) && !multiple(aeNs, HALF_PERIOD_50HZ_NS) ? votes60 + 1 : 0;
            if (votes50 >= FLICKER_FRAMES) suspectHalfNs = HALF_PERIOD_50HZ_NS;
            else if (votes60 >= FLICKER_FRAMES) suspectHalfNs = HALF_PERIOD_60HZ_NS;
        }

        private void latchSuspect() {
            flickerHalfNs = suspectHalfNs;
            suspectHalfNs = 0;
        }

        /**
         * A ring frame's row profile: before the take-over a settled AE frame at whole half periods is the band-free reference;
         * under the ceiling a frame that is no whole multiple of the suggested half period shows the lamps' bands against it, or
         * none.
         */
        private void checkBands(long profileTs, float[] profile, long nowNs) {
            if (profile == null || profileTs == 0 || profileTs == usedProfileTs) return;
            final int f = find(profileTs);
            if (f < 0) return;
            usedProfileTs = profileTs;
            if (!active) {
                if (!recAeOff[f] && recSettled[f] && (multiple(recNs[f], HALF_PERIOD_50HZ_NS) || multiple(recNs[f], HALF_PERIOD_60HZ_NS)))
                    refProfile = profile;
                return;
            }
            bands = recAeOff[f] ? bandAmplitude(profile, refProfile) : Double.NaN;
            if (suspectHalfNs <= 0 || flickerHalfNs > 0 || !recAeOff[f] || multiple(recNs[f], suspectHalfNs)) return;
            if (Double.isNaN(bands)) {
                latchSuspect(); // no band-free reference to judge by
            } else if (bands > BAND_MAX) {
                if (++bandHits >= BAND_HITS) latchSuspect();
            } else if (++bandMisses >= BAND_MISSES) {
                bandFree = true;
                suspectHalfNs = 0;
                votes50 = votes60 = 0;
            }
        }

        /**
         * Row bands of {@code profile} against the band-free {@code reference} (row profiles of one scene): the ratio less its
         * linear trend (vignetting, an exposure step), a median of three against one odd bin, half its peak to peak; NaN when
         * the profiles do not fit.
         */
        public static double bandAmplitude(float[] profile, float[] reference) {
            if (profile == null || reference == null || profile.length != reference.length || profile.length < 8) return Double.NaN;
            final int n = profile.length;
            final double[] q = new double[n];
            double sx = 0, sy = 0, sxx = 0, sxy = 0;
            for (int i = 0; i < n; i++) {
                if (!(reference[i] > 0) || !(profile[i] > 0)) return Double.NaN;
                q[i] = profile[i] / reference[i];
                sx += i;
                sy += q[i];
                sxx += (double) i * i;
                sxy += i * q[i];
            }
            final double slope = (n * sxy - sx * sy) / (n * sxx - sx * sx), mean = sy / n, offset = mean - slope * sx / n;
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = 1; i < n - 1; i++) {
                final double a = q[i - 1] - (offset + slope * (i - 1)), b = q[i] - (offset + slope * i), c = q[i + 1] - (offset + slope * (i + 1));
                final double m = Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
                lo = Math.min(lo, m);
                hi = Math.max(hi, m);
            }
            return (hi - lo) / 2 / mean;
        }

        static boolean multiple(long ns, long halfNs) {
            if (ns < halfNs) return false;
            double k = (double) ns / halfNs;
            return Math.abs(k - Math.round(k)) * halfNs <= 0.005 * halfNs * Math.round(k);
        }
    }
}
