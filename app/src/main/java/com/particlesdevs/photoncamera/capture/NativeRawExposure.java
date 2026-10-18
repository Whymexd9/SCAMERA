package com.particlesdevs.photoncamera.capture;

import android.util.Range;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Locale;

/**
 * P192: the own auto exposure of a native FULLRES session whose HAL has no statistics (OPPO Find X7 Ultra: AE stays SEARCHING
 * in the NoRemosaic modes). It meters the native RAW frames themselves - the level the exposure gives in this mode, not a value
 * carried over from the binned mode - and moves the manual exposure until the median luminance of the frame sits at
 * {@link #SETPOINT} of the sensor's range (highlights kept under {@link #HIGHLIGHT} while the median stays within two stops),
 * then holds it inside a dead band. A change waits until the frames show the last one (the result's exposure and sensitivity) or
 * {@link #SETTLE_FRAMES} frames passed. The exposure goes to the shutter up to {@link #HANDHELD_NS}, then the sensitivity, then
 * the shutter up to the frame time; above 10 ms it is a multiple of 10 ms (50 Hz light does not band).
 * <p>
 * P212a: the sensitivity stops at what the mode really delivers ({@link #isoCap(int)}, ExposureLimits: the PHY110's native modes
 * give ISO 1600 at most although the camera declares 6400). Before, the split raised the ISO to 6400 at 30 ms instead of
 * lengthening the shutter: the frames came 2 EV short of the request, the request was never 'shown' and was rebuilt every 6
 * frames (139 rebuilds in 323 s, log22), and the shutter flipped 30 / 40 ms (0.41 EV pumping). With the cap the shutter takes the
 * frame time once the ISO is at the cap (40 ms at 100 Hz, 41.67 ms at 120 Hz after the flicker snap): longer than HANDHELD_NS, the
 * same blur trade-off as before once the ISO was maxed (the own AE does not take HalMotion's shutter ceiling).
 * <p>
 * P212c: {@link #meter2} meters what the photo shows (the output crop, centre and tap weighted, shading- and white-balance-corrected
 * at today's scale) with log bins and per-site clipping, and {@link #smoothing(boolean)} adds an EMA and a hysteresis against the
 * limit cycle of a lamp at the highlight threshold (log21 L1295-1406: 12 direction flips in 23 lines).
 */
final class NativeRawExposure {
    /** Median luminance of the frame (linear, a fraction of white over black) the exposure aims at. */
    static final float SETPOINT = 0.12f;
    /** The 98th percentile of the brightest channel is kept under this, as long as the median stays two stops from SETPOINT. */
    static final float HIGHLIGHT = 0.85f;
    static final float DEAD_BAND_EV = 0.15f;
    static final float STEP = 0.7f;
    static final int SETTLE_FRAMES = 6;
    static final long HANDHELD_NS = 33_333_333L, FLICKER_NS = 10_000_000L;
    /** P212c smoothing: start moving at START_EV off the target, stop under STOP_EV; the EMA weight; frames between rebuilds. */
    static final float START_EV = 0.25f, STOP_EV = 0.08f, EMA = 0.3f;
    static final int REBUILD_GAP = 3;
    /** P212a: once a session, a request above the cap after PROBE_AFTER_MS when the exposure is PROBE_DEFICIT_EV short. */
    static final long PROBE_AFTER_MS = 10_000L;
    static final float PROBE_DEFICIT_EV = 0.5f;
    /** P212c: the clip budget of the log-only law ({@link #clipBudget}); a tap region counts this much more in the meter. */
    static final float CLIP_BUDGET = 0.002f, REGION_WEIGHT = 4f;
    private static final double LN2 = Math.log(2);

    private final long minExposure, maxExposure, frameNs;
    private final int minIso, maxIso;
    private long exposureNs;
    private int iso;
    private int waited;
    private boolean pending;
    /**
     * P209 (owner's X7 Ultra, 2026-10-17: stripes in the FULLRES viewfinder and photo under 60 Hz mains): the lamp flicker period
     * a shutter of a period or longer is a whole number of (FLICKER_NS = 100 Hz until told; 0 = no snap).
     */
    private long flickerNs = FLICKER_NS;
    /** P201: the hand-set sensitivity and shutter (0 = automatic). */
    private int fixedIso;
    private long fixedNs;
    /** P212a: the ISO the mode delivers (MAX_VALUE: none known); the exposure the meter last asked for (ISO-100 ns, 0: none). */
    private int isoCap = Integer.MAX_VALUE;
    private double wanted;
    private boolean probed;
    /** P212b: metered frames in a row that asked for no change (inside the band, or at a limit). */
    private int quiet;
    /** P212c: EMA and hysteresis on (scam_dev.txt "native_ae_smooth"); ln(p50 / shot), ln(p98 / shot) smoothed; moving. */
    private boolean smooth, moving;
    private double emaP50 = Double.NaN, emaP98 = Double.NaN;
    private int sinceRebuild = REBUILD_GAP;
    /** P212c: the compensation of the last update (a change moves the exposure even when it is smaller than START_EV). */
    private float lastEv = Float.NaN;

    /** Ranges of the camera; the frame time bounds the shutter (24 fps native: 41.7 ms); a first exposure to start from. */
    NativeRawExposure(@Nullable Range<Long> exposureRange, @Nullable Range<Integer> isoRange, long frameNs, long startExposureNs,
                      int startIso) {
        minExposure = exposureRange == null ? 100_000L : Math.max(1L, exposureRange.getLower());
        maxExposure = exposureRange == null ? 200_000_000L : exposureRange.getUpper();
        minIso = isoRange == null ? 100 : Math.max(1, isoRange.getLower());
        maxIso = isoRange == null ? 3200 : Math.max(minIso, isoRange.getUpper());
        this.frameNs = frameNs > 0 ? frameNs : 41_666_666L;
        final double start = startExposureNs > 0 && startIso > 0 ? (double) startExposureNs * startIso / 100.0 : 10_000_000.0 * 4;
        split(start);
    }

    /** A shutter of a flicker period or longer as whole periods (rounded down). */
    private long snap(long t) {
        return flickerNs > 0 && t >= flickerNs ? Math.max(flickerNs, (long) Math.floor((double) t / flickerNs + 1e-6) * flickerNs) : t;
    }

    /**
     * P209: the lamp flicker in Hz (100 = 50 Hz mains, 120 = 60 Hz mains, 0 = no snap: PreferenceKeys.getAntibandingHz, «Авто» the
     * camera's report / the region). True when the exposure changed (the requests must be rebuilt).
     */
    boolean flicker(int hz) {
        final long period = hz > 0 ? Math.round(1e9 / hz) : 0L;
        if (period == flickerNs) return false;
        flickerNs = period;
        resetSmoothing(); // P212c: another banding, another level
        final long before = exposureNs;
        final int beforeIso = iso;
        if (fixedNs <= 0) split(total()); // a hand-set shutter stays as set
        if (exposureNs == before && iso == beforeIso) return false;
        pending = true;
        waited = 0;
        quiet = 0;
        return true;
    }

    /**
     * P212a: the ISO the mode really delivers for AE-off requests (ExposureLimits.isoCap, learned and stored; MAX_VALUE or 0 = none
     * known). A change re-splits what the meter last asked for (the requests above a fallen cap come back clamped; a cap that rose
     * gives the gain the frames were short of). ExposureLimits moves the cap only on proof - two clamped results, or a result
     * delivered above it - so a request answered at the cap within the rounding changes nothing here. True when the exposure
     * changed (the requests must be rebuilt).
     */
    boolean isoCap(int cap) {
        final int c = cap > 0 ? cap : Integer.MAX_VALUE;
        if (c == isoCap) return false;
        isoCap = c;
        final long before = exposureNs;
        final int beforeIso = iso;
        split(wanted > 0 ? wanted : total());
        if (exposureNs == before && iso == beforeIso) return false;
        pending = true;
        waited = 0;
        quiet = 0;
        return true;
    }

    /** P212a: the cap in use (MAX_VALUE: none). */
    int isoCap() { return isoCap; }

    /** P212a: the highest ISO a request may carry: the declared range, under the delivered cap. */
    int effMaxIso() { return Math.max(minIso, Math.min(maxIso, isoCap)); }

    /** P212a: the largest exposure the mode delivers (ISO-100 ns): the frame time at the delivered ISO. */
    double highest() { return (double) Math.min(maxExposure, frameNs) * effMaxIso() / 100.0; }

    /** P212a: how far (EV) the exposure the meter asked for is above the largest the mode delivers (0: none). */
    double deficitEv() {
        return wanted > 0 ? Math.max(0.0, Math.log(wanted / highest()) / LN2) : 0.0;
    }

    long exposureNs() { return exposureNs; }
    int iso() { return iso; }
    boolean pending() { return pending; }
    int waited() { return waited; }
    /** The frame time of the requests: the native mode's own (24 fps), longer only for a longer shutter. */
    long frameDurationNs() { return Math.max(frameNs, exposureNs); }

    /**
     * P201: the hand-set sensitivity / shutter (0 = automatic). Both by hand: the exposure is exactly them, no metering; one by
     * hand: the metering moves the other only. True when the exposure changed (the requests must be rebuilt). P212a: a hand-set
     * ISO stops at what the mode delivers (an ISO 3200 asked of the PHY110's native mode is ISO 1600 in the frames).
     */
    boolean setManual(int manualIso, long manualNs) {
        final int asked = manualIso;
        manualIso = manualIso > 0 ? Math.max(minIso, Math.min(effMaxIso(), manualIso)) : 0;
        manualNs = manualNs > 0 ? Math.max(minExposure, Math.min(maxExposure, manualNs)) : 0;
        if (manualIso == fixedIso && manualNs == fixedNs) return false;
        if (manualIso > 0 && asked > manualIso && manualIso < maxIso)
            com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", "native AE: hand-set ISO " + asked + " above the mode's cap, "
                    + manualIso + " asked");
        fixedIso = manualIso;
        fixedNs = manualNs;
        final long before = exposureNs;
        final int beforeIso = iso;
        split(total());
        pending = false;
        return exposureNs != before || iso != beforeIso;
    }

    boolean manual() {
        return fixedIso > 0 && fixedNs > 0;
    }

    /** P212b: ISO or shutter set by hand (the exposure is not the AE's own). */
    boolean handSet() {
        return fixedIso > 0 || fixedNs > 0;
    }

    /** Exposure as ISO-100 nanoseconds. */
    double total() { return (double) exposureNs * iso / 100.0; }

    /**
     * P212b: the exposure to start from (ISO-100 ns; NativeAeSeed): split as any request (the cap, the flicker), nothing pending,
     * the smoothing from scratch. Nothing happens for none.
     */
    void seed(double totalNs) {
        if (!(totalNs > 0) || Double.isInfinite(totalNs)) return;
        split(totalNs);
        pending = false;
        waited = 0;
        quiet = 0;
        wanted = 0;
        moving = false;
        resetSmoothing();
    }

    /** P212c: the EMA / hysteresis law (scam_dev.txt "native_ae_smooth", on by default in the app; off: the P192 law). */
    void smoothing(boolean on) {
        if (on == smooth) return;
        smooth = on;
        moving = false;
        resetSmoothing();
    }

    private void resetSmoothing() {
        emaP50 = Double.NaN;
        emaP98 = Double.NaN;
    }

    /**
     * P212b: the exposure holds - nothing pending, and {@link #SETTLE_FRAMES} metered frames in a row asked for no change: inside
     * the band, or at the deliverable limit (the ISO cap at the frame time: 184 of 312 logged night updates asked for more than
     * the delivered ISO 1600, a press there is as settled as it gets). Both ISO and shutter by hand: always.
     */
    boolean settled() {
        return manual() || !pending && quiet >= SETTLE_FRAMES;
    }

    /**
     * P212a: the ISO of the session's one probe (0: none now): after PROBE_AFTER_MS of the session, the cap known and below the
     * declared range, the exposure more than PROBE_DEFICIT_EV short of the meter, nothing pending, no hand-set ISO - the cap times
     * 1.5 (at most the declared range). A firmware that gives more gain answers it above the cap, which clears the cap
     * (ExposureLimits); the caller sends it only while no shot is pending and keeps its frame out of the ring and the plan.
     */
    int probeIso(long sessionMs) {
        if (probed || fixedIso > 0 || pending || isoCap >= maxIso || sessionMs < PROBE_AFTER_MS || deficitEv() <= PROBE_DEFICIT_EV) return 0;
        probed = true;
        return (int) Math.min(maxIso, Math.round(isoCap * 1.5));
    }

    /**
     * One metered frame ({@code p50}, {@code p98} of {@link #meter} / {@link #meter2}) taken at {@code shotExposureNs} /
     * {@code shotIso}, with the user's compensation {@code ev}: true when the exposure changed (the requests must be rebuilt).
     */
    boolean update(float p50, float p98, long shotExposureNs, int shotIso, float ev) {
        if (manual()) return false; // P201: ISO and shutter by hand
        if (sinceRebuild < REBUILD_GAP) sinceRebuild++;
        if (pending) {
            final boolean shown = shotIso > 0 && Math.abs(shotExposureNs - exposureNs) <= Math.max(20_000L, exposureNs / 30)
                    && Math.abs(shotIso - iso) <= Math.max(2, iso / 30);
            if (!shown && ++waited < SETTLE_FRAMES) return false;
            pending = false;
        }
        if (!(p50 >= 0f) || !(p98 >= 0f)) return false;
        final double shot = shotExposureNs > 0 && shotIso > 0 ? (double) shotExposureNs * shotIso / 100.0 : total();
        double mp50 = p50, mp98 = p98;
        if (smooth) {
            // P212c: the frame's levels per unit of its own exposure, smoothed; a jump of more than a stop is a new scene
            final double l50 = Math.log(Math.max(p50, 1e-5f) / shot), l98 = Math.log(Math.max(p98, 1e-5f) / shot);
            if (Double.isNaN(emaP50) || Math.abs(l50 - emaP50) > LN2 || Math.abs(l98 - emaP98) > LN2) {
                emaP50 = l50;
                emaP98 = l98;
            } else {
                emaP50 += EMA * (l50 - emaP50);
                emaP98 += EMA * (l98 - emaP98);
            }
            mp50 = Math.exp(emaP50) * shot;
            mp98 = Math.exp(emaP98) * shot;
        }
        final double m = multiplier((float) mp50, (float) mp98, ev);
        wanted = shot * m;
        final double off = Math.abs(Math.log(m) / LN2);
        final boolean evChanged = !Float.isNaN(lastEv) && Math.abs(ev - lastEv) > 1e-3f;
        lastEv = ev;
        if (smooth) {
            // P212c hysteresis: a lamp at the highlight threshold (p98 0.75 <-> 0.95, ±0.16 EV) no longer moves the exposure; the
            // user's compensation does, in any step (a 1/6 EV press is under START_EV)
            moving = moving || evChanged ? off >= STOP_EV : off >= START_EV;
            if (!moving) { quiet++; return false; }
            if (sinceRebuild < REBUILD_GAP) { quiet = 0; return false; }
        } else if (off < DEAD_BAND_EV) {
            quiet++;
            return false;
        }
        final double before = total();
        split(shot * Math.pow(m, smooth && off > 1 ? 1.0 : STEP));
        if (Math.abs(total() - before) <= before * 0.01) { quiet++; return false; } // at a limit: nothing more to give
        if (smooth && Math.abs(Math.log(total() / shot) / LN2) > 1) resetSmoothing();
        pending = true;
        waited = 0;
        quiet = 0;
        sinceRebuild = 0;
        return true;
    }

    /** The factor that brings the median to the setpoint (times 2^ev), lowered for the highlights, at most 2 stops below. */
    static double multiplier(float p50, float p98, float ev) {
        final double target = SETPOINT * Math.pow(2, ev);
        double m = target / Math.max(p50, 1e-4f);
        if (p98 > 0f && p98 * m > HIGHLIGHT) m = Math.max(HIGHLIGHT / p98, target / 4 / Math.max(p50, 1e-4f));
        return Math.max(1 / 16.0, Math.min(16.0, m));
    }

    /**
     * P212c (M9's law, LOG ONLY - the AE does not use it yet): a clip budget instead of the hard p98 rule. More than twice
     * CLIP_BUDGET of the cells clipped steps down by 0.3-2 EV; else a peak over 0.9 blends softly (lim + (m - lim) / 3, lim =
     * 0.9 / P, the ArkCore rule); a raise only while the clip it predicts stays under half the budget; never under the two-stop
     * floor of today's law (daylight shots already clip 1-3 % of the cells, the floor keeps them as bright as today).
     */
    static double clipBudget(Metered m, float ev) {
        if (m == null) return 1.0;
        final double target = SETPOINT * Math.pow(2, ev), p50 = Math.max(m.p50, 1e-4f);
        double k = target / p50;
        if (m.clip > 2 * CLIP_BUDGET) {
            k = Math.min(k, Math.pow(2, -Math.max(0.3, Math.min(2.0, 0.3 + 0.5 * Math.log(m.clip / CLIP_BUDGET) / LN2))));
        } else if (m.peak > 0f && m.peak * k > 0.9) {
            final double lim = 0.9 / m.peak;
            k = lim + (k - lim) * 0.33;
        }
        if (k > 1 && m.clipAfter(k) > CLIP_BUDGET / 2) k = 1;
        k = Math.max(k, target / 4 / p50);
        return Math.max(1 / 16.0, Math.min(16.0, k));
    }

    /** The exposure (ISO-100 ns) as shutter and sensitivity: the shutter up to HANDHELD_NS, the sensitivity, the frame time. */
    void split(double total) {
        final int topIso = effMaxIso();
        if (fixedIso > 0 || fixedNs > 0) { // P201: what is set by hand stays, the other follows the exposure
            long t = fixedNs > 0 ? fixedNs : Math.round(total * 100.0 / fixedIso);
            if (fixedNs <= 0) {
                t = Math.max(minExposure, Math.min(Math.min(maxExposure, frameNs), t));
                t = snap(t);
            }
            final int s = fixedIso > 0 ? fixedIso : (int) Math.round(total * 100.0 / Math.max(1L, t));
            exposureNs = Math.max(minExposure, Math.min(maxExposure, t));
            iso = Math.max(minIso, Math.min(topIso, s));
            return;
        }
        final double lowest = (double) minExposure * minIso / 100.0, highest = highest();
        total = Math.max(lowest, Math.min(highest, total));
        long t = Math.round(total * 100.0 / minIso);
        int s = minIso;
        final long handheld = Math.min(Math.min(HANDHELD_NS, frameNs), maxExposure);
        if (t > handheld) {
            t = handheld;
            s = (int) Math.round(total * 100.0 / t);
            if (s > topIso) {
                s = topIso;
                t = Math.min(Math.min(frameNs, maxExposure), Math.round(total * 100.0 / s));
            }
        }
        if (flickerNs > 0 && t >= flickerNs) {
            t = snap(t);
            s = (int) Math.max(minIso, Math.min(topIso, Math.round(total * 100.0 / t)));
        }
        exposureNs = Math.max(minExposure, Math.min(maxExposure, t));
        iso = Math.max(minIso, Math.min(topIso, s));
    }

    /**
     * {median luminance, 98th percentile of the brightest channel} of a RAW frame (16-bit little-endian rows of {@code stride}
     * bytes), colour blocks of {@code block} sites in the 2x2 order {@code cfa}, over a grid of about 64 x 48 colour cells; null
     * when nothing was read.
     */
    @Nullable
    static float[] meter(ByteBuffer raw, int width, int height, int stride, int cfa, int block, float[] black, float white) {
        if (raw == null || block < 1 || cfa < 0 || cfa > 3 || black == null || black.length < 4 || !(white > 0)) return null;
        final int cell = 2 * block, cellsX = width / cell, cellsY = height / cell;
        if (cellsX < 1 || cellsY < 1) return null;
        final ShortBuffer px = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        final int row = stride / 2, sx = Math.max(1, cellsX / 64), sy = Math.max(1, cellsY / 48);
        final int red = cfa, blue = 3 - cfa;
        final int[] lum = new int[1024], peak = new int[1024];
        final float[] c = new float[4];
        int n = 0;
        for (int cy = sy / 2; cy < cellsY; cy += sy) {
            for (int cx = sx / 2; cx < cellsX; cx += sx) {
                for (int ph = 0; ph < 4; ph++) {
                    final int x0 = cx * cell + (ph & 1) * block, y0 = cy * cell + (ph >> 1) * block;
                    int sum = 0;
                    for (int y = 0; y < block; y++) {
                        final int base = (y0 + y) * row + x0;
                        if (base + block > px.limit()) return null;
                        for (int x = 0; x < block; x++) sum += px.get(base + x) & 0xFFFF;
                    }
                    c[ph] = Math.max(0f, ((float) sum / (block * block) - black[ph]) / Math.max(1f, white - black[ph]));
                }
                final float r = c[red], g = 0.5f * (c[red ^ 1] + c[red ^ 2]), b = c[blue];
                final float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                lum[Math.min(1023, (int) (l * 1023f))]++;
                peak[Math.min(1023, (int) (Math.max(r, Math.max(g, b)) * 1023f))]++;
                n++;
            }
        }
        if (n == 0) return null;
        return new float[]{percentile(lum, n, 0.5f), percentile(peak, n, 0.98f)};
    }

    /**
     * P212c: what {@link #meter2} weights and corrects. Fractions of the RAW frame; the defaults meter the whole frame, centre
     * weighted, without shading or white balance.
     */
    static final class MeterSetup {
        /** The output crop {left, top, right, bottom}: the cells are read inside it only. */
        float left, top, right = 1f, bottom = 1f;
        /** Tapped AE regions ({left, top, right, bottom} each, null: none): cells inside count REGION_WEIGHT times. */
        @Nullable float[][] regions;
        /**
         * Lens shading gains in LensShadingMap.copyGainFactors order (R, G even, G odd, B per point, rows of {@code gainCols});
         * null: none (the result's own map, else - W1 - a stored plain one).
         */
        @Nullable float[] gains;
        int gainCols, gainRows;
        /** The white point (r / g, b / g) the luminance is balanced by. */
        float neutralR = 1f, neutralB = 1f;
        boolean centre = true;
    }

    /** P212c: one frame of {@link #meter2}. */
    static final class Metered {
        /** Weighted median luminance (today's scale), 98th percentile of the cells' peaks (a clipped site: 1), log-mean. */
        final float p50, p98, logMean;
        /** Fraction of the cropped cells with a clipped site; 99.5th percentile of the unclipped cells' peaks. */
        final float clip, peak;
        final int cells;
        final boolean shaded;
        private final int[] unclipped;
        private final int clipped, inside;

        Metered(float p50, float p98, float logMean, float clip, float peak, int cells, boolean shaded, int[] unclipped, int clipped,
                int inside) {
            this.p50 = p50;
            this.p98 = p98;
            this.logMean = logMean;
            this.clip = clip;
            this.peak = peak;
            this.cells = cells;
            this.shaded = shaded;
            this.unclipped = unclipped;
            this.clipped = clipped;
            this.inside = inside;
        }

        /** The fraction of the cropped cells clipped at {@code k} times the exposure. */
        float clipAfter(double k) {
            if (inside <= 0) return 0f;
            int n = clipped;
            for (int i = 0; i < unclipped.length; i++) if ((i + 0.5) / unclipped.length * k >= 0.98) n += unclipped[i];
            return (float) n / inside;
        }
    }

    static final int LOG_BINS = 512;
    static final float LOG_FLOOR = 1f / 65536f; // 2^-16: 16 stops in LOG_BINS bins, 1/32 EV each

    /**
     * P212c: the meter of the native AE. The cells of {@link #meter} (about 64 x 48 of them, now over the output crop; the per-site
     * maximum costs no extra reads), with:
     * <ul>
     * <li>per-site clipping: a cell with any site at 0.98 of white counts as clipped (peak 1), so G sites clipped inside a Quad
     * block are not hidden by the block mean; peaks are raw values (the sensor clips, not the corrected image);</li>
     * <li>the luminance shading-corrected (the gains normalised by their median over the frame, so a flat field keeps today's
     * median and SETPOINT stays) and white-balanced at today's scale (a neutral grey keeps its luminance);</li>
     * <li>nothing outside the output crop; weights 1 + 1.5 exp(-r^2 / 0.18) around its centre, REGION_WEIGHT inside a tapped region;</li>
     * <li>log bins (1/32 EV) for the median: the night medians of 0.001-0.003 were bins 1-3 of the 1024 linear ones.</li>
     * </ul>
     * Null when nothing was read.
     */
    @Nullable
    static Metered meter2(ByteBuffer raw, int width, int height, int stride, int cfa, int block, float[] black, float white,
                          @Nullable MeterSetup setup) {
        if (raw == null || block < 1 || cfa < 0 || cfa > 3 || black == null || black.length < 4 || !(white > 0)) return null;
        final int cell = 2 * block, cellsX = width / cell, cellsY = height / cell;
        if (cellsX < 1 || cellsY < 1) return null;
        final MeterSetup s;
        if (setup != null) s = setup;
        else {
            s = new MeterSetup();
            s.centre = false;
        }
        float cl = Math.max(0f, s.left), ct = Math.max(0f, s.top), cr = Math.min(1f, s.right), cb = Math.min(1f, s.bottom);
        if (!(cr - cl >= 0.02f) || !(cb - ct >= 0.02f)) { cl = 0f; ct = 0f; cr = 1f; cb = 1f; }
        // the grid of about 64 x 48 cells over the crop (a zoomed photo is metered as finely as the whole frame)
        final int cx0 = Math.min(cellsX - 1, (int) Math.floor(cl * cellsX)), cx1 = Math.max(cx0 + 1, Math.min(cellsX, (int) Math.ceil(cr * cellsX)));
        final int cy0 = Math.min(cellsY - 1, (int) Math.floor(ct * cellsY)), cy1 = Math.max(cy0 + 1, Math.min(cellsY, (int) Math.ceil(cb * cellsY)));
        final ShortBuffer px = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        final int row = stride / 2, sx = Math.max(1, (cx1 - cx0) / 64), sy = Math.max(1, (cy1 - cy0) / 48);
        final int red = cfa, blue = 3 - cfa;
        final int cap = ((cx1 - cx0 - sx / 2 + sx - 1) / sx) * ((cy1 - cy0 - sy / 2 + sy - 1) / sy);
        if (cap <= 0) return null;
        final float[] lum = new float[cap], weight = new float[cap];
        final int[] unclipped = new int[1024];
        final float[] c = new float[4], clipAt = new float[4];
        for (int ph = 0; ph < 4; ph++) clipAt[ph] = black[ph] + 0.98f * (white - black[ph]);
        final float[] gains = s.gains;
        final int gc = s.gainCols, gr = s.gainRows;
        final boolean shaded = gains != null && gc > 1 && gr > 1 && gains.length >= 4 * gc * gr;
        // the shading gains over their median on the whole frame: a flat field keeps today's (raw, whole-frame) median, so the
        // SETPOINT stays (the PHY110 camera 2 probe: LSC-corrected / raw median 1.98, corner gains up to 4.7-5.0)
        final float norm = shaded ? gainMedian(gains, gc, gr) : 1f;
        final float nr = s.neutralR > 0.05f && s.neutralR < 20f ? s.neutralR : 1f, nb = s.neutralB > 0.05f && s.neutralB < 20f ? s.neutralB : 1f;
        final float wbScale = 0.2126f * nr + 0.7152f + 0.0722f * nb; // a neutral grey keeps today's luminance
        final float[] g3 = new float[3];
        int n = 0, clipped = 0;
        for (int cy = cy0 + sy / 2; cy < cy1; cy += sy) {
            for (int cx = cx0 + sx / 2; cx < cx1 && n < cap; cx += sx) {
                boolean clip = false;
                for (int ph = 0; ph < 4; ph++) {
                    final int x0 = cx * cell + (ph & 1) * block, y0 = cy * cell + (ph >> 1) * block;
                    int sum = 0, max = 0;
                    for (int y = 0; y < block; y++) {
                        final int base = (y0 + y) * row + x0;
                        if (base + block > px.limit()) return null;
                        for (int x = 0; x < block; x++) {
                            final int v = px.get(base + x) & 0xFFFF;
                            sum += v;
                            if (v > max) max = v;
                        }
                    }
                    c[ph] = Math.max(0f, ((float) sum / (block * block) - black[ph]) / Math.max(1f, white - black[ph]));
                    if (max >= clipAt[ph]) clip = true;
                }
                final float r = c[red], g = 0.5f * (c[red ^ 1] + c[red ^ 2]), b = c[blue];
                final float u = (cx * cell + block) / (float) width, v = (cy * cell + block) / (float) height;
                float sr = 1f, sg = 1f, sb = 1f;
                if (shaded && gainAt(gains, gc, gr, u, v, g3)) {
                    sr = g3[0] / norm;
                    sg = g3[1] / norm;
                    sb = g3[2] / norm;
                }
                lum[n] = (0.2126f * r * sr / nr + 0.7152f * g * sg + 0.0722f * b * sb / nb) * wbScale;
                float w = 1f;
                if (s.centre) {
                    final float du = (u - cl) / (cr - cl) - 0.5f, dv = (v - ct) / (cb - ct) - 0.5f;
                    w += 1.5f * (float) Math.exp(-(du * du + dv * dv) / 0.18f);
                }
                if (s.regions != null) for (float[] q : s.regions)
                    if (q != null && q.length >= 4 && u >= q[0] && u < q[2] && v >= q[1] && v < q[3]) { w *= REGION_WEIGHT; break; }
                if (clip) clipped++;
                else unclipped[Math.min(1023, (int) (Math.max(r, Math.max(g, b)) * 1023f))]++;
                weight[n++] = w;
            }
        }
        if (n == 0) return null;
        final double[] hist = new double[LOG_BINS];
        double wsum = 0, lsum = 0;
        for (int i = 0; i < n; i++) {
            hist[logBin(lum[i])] += weight[i];
            wsum += weight[i];
            lsum += weight[i] * Math.log(Math.max(lum[i], LOG_FLOOR));
        }
        final int[] peaks = unclipped.clone();
        peaks[1023] += clipped;
        final float p98 = percentile(peaks, n, 0.98f);
        final float peak = n > clipped ? percentile(unclipped, n - clipped, 0.995f) : 1f;
        return new Metered(logPercentile(hist, wsum, 0.5f), p98, (float) Math.exp(lsum / wsum), (float) clipped / n, peak, n, shaded,
                unclipped, clipped, n);
    }

    /** The shading gains {R, G (mean of the two), B} at (u, v) of the frame, bilinear; false when the map gives no positive gain. */
    private static boolean gainAt(float[] g, int cols, int rows, float u, float v, float[] out) {
        final float fx = Math.min(cols - 1.0001f, Math.max(0f, u * (cols - 1))), fy = Math.min(rows - 1.0001f, Math.max(0f, v * (rows - 1)));
        final int ix = (int) fx, iy = (int) fy;
        final float ax = fx - ix, ay = fy - iy;
        final int i00 = 4 * (iy * cols + ix), i10 = i00 + 4, i01 = i00 + 4 * cols, i11 = i01 + 4;
        out[0] = bilinear(g, i00, i10, i01, i11, 0, ax, ay);
        out[1] = 0.5f * (bilinear(g, i00, i10, i01, i11, 1, ax, ay) + bilinear(g, i00, i10, i01, i11, 2, ax, ay));
        out[2] = bilinear(g, i00, i10, i01, i11, 3, ax, ay);
        return out[0] > 0f && out[1] > 0f && out[2] > 0f;
    }

    /** The last map's median (the meter reads the same map frame after frame: the results carry one object). */
    private static volatile Object[] lastMedian;

    /** The median green gain of a shading map over a 64 x 48 grid of the whole frame (1 when it gives none). */
    static float gainMedian(float[] g, int cols, int rows) {
        final Object[] last = lastMedian;
        if (last != null && last[0] == g && (Integer) last[1] == cols && (Integer) last[2] == rows) return (Float) last[3];
        final float value = gainMedianOf(g, cols, rows);
        lastMedian = new Object[]{g, cols, rows, value};
        return value;
    }

    private static float gainMedianOf(float[] g, int cols, int rows) {
        final float[] at = new float[3], greens = new float[64 * 48];
        int n = 0;
        for (int y = 0; y < 48; y++)
            for (int x = 0; x < 64; x++)
                if (gainAt(g, cols, rows, (x + 0.5f) / 64f, (y + 0.5f) / 48f, at)) greens[n++] = at[1];
        if (n == 0) return 1f;
        final float[] sorted = Arrays.copyOf(greens, n);
        Arrays.sort(sorted);
        return sorted[n / 2];
    }

    private static float bilinear(float[] g, int i00, int i10, int i01, int i11, int ch, float ax, float ay) {
        final float top = g[i00 + ch] + (g[i10 + ch] - g[i00 + ch]) * ax, bottom = g[i01 + ch] + (g[i11 + ch] - g[i01 + ch]) * ax;
        return top + (bottom - top) * ay;
    }

    static int logBin(float l) {
        if (!(l > LOG_FLOOR)) return 0;
        return Math.min(LOG_BINS - 1, (int) ((Math.log(l) / LN2 + 16.0) * LOG_BINS / 16.0));
    }

    /** The bin centre (geometric) of the weighted {@code fraction} of a log histogram ({@link #logBin}). */
    static float logPercentile(double[] hist, double total, float fraction) {
        if (!(total > 0)) return 0f;
        final double target = total * fraction;
        double sum = 0;
        for (int i = 0; i < hist.length; i++) {
            sum += hist[i];
            if (sum >= target) return (float) Math.pow(2, -16.0 + (i + 0.5) * 16.0 / hist.length);
        }
        return 1f;
    }

    static float percentile(int[] hist, int total, float fraction) {
        final int target = Math.max(1, (int) Math.ceil(total * fraction));
        int sum = 0;
        for (int i = 0; i < hist.length; i++) {
            sum += hist[i];
            if (sum >= target) return (i + 0.5f) / hist.length;
        }
        return 1f;
    }

    /** P212c: the state for the trace line. */
    String state() {
        return String.format(Locale.ROOT, "req %s pending %b waited %d moving %b quiet %d settled %b deficit %.2f cap %s", this, pending,
                waited, moving, quiet, settled(), deficitEv(), isoCap == Integer.MAX_VALUE ? "-" : String.valueOf(isoCap));
    }

    @Override public String toString() {
        return String.format(Locale.ROOT, "%.3f ms ISO %d", exposureNs / 1e6, iso);
    }
}
