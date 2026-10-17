package com.particlesdevs.photoncamera.capture;

import android.util.Range;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Locale;

/**
 * P192: the own auto exposure of a native FULLRES session whose HAL has no statistics (OPPO Find X7 Ultra: AE stays SEARCHING
 * in the NoRemosaic modes). It meters the native RAW frames themselves - the level the exposure gives in this mode, not a value
 * carried over from the binned mode - and moves the manual exposure until the median luminance of the frame sits at
 * {@link #SETPOINT} of the sensor's range (highlights kept under {@link #HIGHLIGHT} while the median stays within two stops),
 * then holds it inside a dead band. A change waits until the frames show the last one (the result's exposure and sensitivity) or
 * {@link #SETTLE_FRAMES} frames passed. The exposure goes to the shutter up to {@link #HANDHELD_NS}, then the sensitivity, then
 * the shutter up to the frame time; above 10 ms it is a multiple of 10 ms (50 Hz light does not band).
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

    private final long minExposure, maxExposure, frameNs;
    private final int minIso, maxIso;
    private long exposureNs;
    private int iso;
    private int waited;
    private boolean pending;
    /** P201: the hand-set sensitivity and shutter (0 = automatic). */
    private int fixedIso;
    private long fixedNs;

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

    long exposureNs() { return exposureNs; }
    int iso() { return iso; }
    /** The frame time of the requests: the native mode's own (24 fps), longer only for a longer shutter. */
    long frameDurationNs() { return Math.max(frameNs, exposureNs); }

    /**
     * P201: the hand-set sensitivity / shutter (0 = automatic). Both by hand: the exposure is exactly them, no metering; one by
     * hand: the metering moves the other only. True when the exposure changed (the requests must be rebuilt).
     */
    boolean setManual(int manualIso, long manualNs) {
        manualIso = manualIso > 0 ? Math.max(minIso, Math.min(maxIso, manualIso)) : 0;
        manualNs = manualNs > 0 ? Math.max(minExposure, Math.min(maxExposure, manualNs)) : 0;
        if (manualIso == fixedIso && manualNs == fixedNs) return false;
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

    /** Exposure as ISO-100 nanoseconds. */
    double total() { return (double) exposureNs * iso / 100.0; }

    /**
     * One metered frame ({@code p50}, {@code p98} of {@link #meter}) taken at {@code shotExposureNs} / {@code shotIso}, with the
     * user's compensation {@code ev}: true when the exposure changed (the requests must be rebuilt).
     */
    boolean update(float p50, float p98, long shotExposureNs, int shotIso, float ev) {
        if (manual()) return false; // P201: ISO and shutter by hand
        if (pending) {
            final boolean shown = shotIso > 0 && Math.abs(shotExposureNs - exposureNs) <= Math.max(20_000L, exposureNs / 30)
                    && Math.abs(shotIso - iso) <= Math.max(2, iso / 30);
            if (!shown && ++waited < SETTLE_FRAMES) return false;
            pending = false;
        }
        if (!(p50 >= 0f) || !(p98 >= 0f)) return false;
        final double shot = shotExposureNs > 0 && shotIso > 0 ? (double) shotExposureNs * shotIso / 100.0 : total();
        final double m = multiplier(p50, p98, ev);
        if (Math.abs(Math.log(m) / Math.log(2)) < DEAD_BAND_EV) return false;
        final double before = total();
        split(shot * Math.pow(m, STEP));
        if (Math.abs(total() - before) <= before * 0.01) return false;
        pending = true;
        waited = 0;
        return true;
    }

    /** The factor that brings the median to the setpoint (times 2^ev), lowered for the highlights, at most 2 stops below. */
    static double multiplier(float p50, float p98, float ev) {
        final double target = SETPOINT * Math.pow(2, ev);
        double m = target / Math.max(p50, 1e-4f);
        if (p98 > 0f && p98 * m > HIGHLIGHT) m = Math.max(HIGHLIGHT / p98, target / 4 / Math.max(p50, 1e-4f));
        return Math.max(1 / 16.0, Math.min(16.0, m));
    }

    /** The exposure (ISO-100 ns) as shutter and sensitivity: the shutter up to HANDHELD_NS, the sensitivity, the frame time. */
    void split(double total) {
        if (fixedIso > 0 || fixedNs > 0) { // P201: what is set by hand stays, the other follows the exposure
            long t = fixedNs > 0 ? fixedNs : Math.round(total * 100.0 / fixedIso);
            if (fixedNs <= 0) {
                t = Math.max(minExposure, Math.min(Math.min(maxExposure, frameNs), t));
                if (t >= FLICKER_NS) t = Math.max(FLICKER_NS, t / FLICKER_NS * FLICKER_NS);
            }
            final int s = fixedIso > 0 ? fixedIso : (int) Math.round(total * 100.0 / Math.max(1L, t));
            exposureNs = Math.max(minExposure, Math.min(maxExposure, t));
            iso = Math.max(minIso, Math.min(maxIso, s));
            return;
        }
        final double lowest = (double) minExposure * minIso / 100.0, highest = (double) Math.min(maxExposure, frameNs) * maxIso / 100.0;
        total = Math.max(lowest, Math.min(highest, total));
        long t = Math.round(total * 100.0 / minIso);
        int s = minIso;
        final long handheld = Math.min(Math.min(HANDHELD_NS, frameNs), maxExposure);
        if (t > handheld) {
            t = handheld;
            s = (int) Math.round(total * 100.0 / t);
            if (s > maxIso) {
                s = maxIso;
                t = Math.min(Math.min(frameNs, maxExposure), Math.round(total * 100.0 / s));
            }
        }
        if (t >= FLICKER_NS) {
            t = Math.max(FLICKER_NS, t / FLICKER_NS * FLICKER_NS);
            s = (int) Math.max(minIso, Math.min(maxIso, Math.round(total * 100.0 / t)));
        }
        exposureNs = Math.max(minExposure, Math.min(maxExposure, t));
        iso = Math.max(minIso, Math.min(maxIso, s));
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

    static float percentile(int[] hist, int total, float fraction) {
        final int target = Math.max(1, (int) Math.ceil(total * fraction));
        int sum = 0;
        for (int i = 0; i < hist.length; i++) {
            sum += hist[i];
            if (sum >= target) return (i + 0.5f) / hist.length;
        }
        return 1f;
    }

    @Override public String toString() {
        return String.format(Locale.ROOT, "%.3f ms ISO %d", exposureNs / 1e6, iso);
    }
}
