package com.particlesdevs.photoncamera.processing.parameters;

/**
 * Shutter/gain factorization with sensor-clipped waypoints, logarithmic
 * interpolation and nearest-period anti-flicker compensation. Arithmetic was
 * checked against 192 executions of libgcam_ae.so function 0x140bbc.
 * The default waypoints are a caiman sensor-14 policy, not device calibration.
 */
public final class TetModel {
    private static final double[] EXP_MS = {0.0, 8.0, 8.0, 100.0, 100.0, 99999.0};
    private static final double[] GAIN = {1.0, 1.0, 4.0, 40.0, 256.0, 256.0};

    public static final class Split {
        public final long exposureNs;
        public final int iso;
        Split(long exposureNs, int iso) {
            this.exposureNs = exposureNs;
            this.iso = iso;
        }
    }

    private TetModel() {}

    /** Pure factorization; package access also permits donor-fixture tests. */
    static double[] factorize(double target, double[] times, double[] gains,
                              double minTime, double maxTime, double maxGain,
                              double period) {
        if (!(minTime > 0) || !Double.isFinite(maxTime) || maxTime < minTime
                || !Double.isFinite(maxGain) || maxGain < 1
                || times.length == 0 || times.length != gains.length) {
            throw new IllegalArgumentException("Invalid exposure model limits");
        }
        // Below-range and non-finite inputs use a bounded minimum. These guards
        // are SCAMERA policy; the donor fixture covers finite positive targets.
        if (Double.isNaN(target) || target <= 0) target = minTime;
        double previousTime = 0, previousGain = 0, previousTet = 0;
        double time = minTime, gain = 1;
        for (int i = 0; i < times.length; i++) {
            if (!Double.isFinite(times[i]) || !Double.isFinite(gains[i])) {
                throw new IllegalArgumentException("Non-finite exposure waypoint");
            }
            time = Math.max(minTime, Math.min(maxTime, times[i]));
            gain = Math.max(1, Math.min(maxGain, gains[i]));
            double tet = time * gain;
            if (i > 0 && tet < previousTet) {
                throw new IllegalArgumentException("Unordered exposure waypoints");
            }
            if (i == 0 && target <= tet) break;
            if (i > 0 && target >= previousTet && target <= tet) {
                double alpha = tet == previousTet ? 0
                        : Math.log(target / previousTet) / Math.log(tet / previousTet);
                alpha = Math.max(0, Math.min(1, alpha));
                time = previousTime * Math.pow(time / previousTime, alpha);
                gain = previousGain * Math.pow(gain / previousGain, alpha);
                break;
            }
            previousTime = time;
            previousGain = gain;
            previousTet = tet;
        }
        if (Double.isFinite(period) && period > 0 && time > period) {
            double product = time * gain;
            double snapped = Math.floor(time / period + 0.5) * period;
            double compensated = product / snapped;
            if (snapped > maxTime || compensated < 1) {
                snapped -= period;
                compensated = product / snapped;
            }
            // The extra minimum-time/finite guards protect arbitrary Camera2
            // limits; no post-snap clamp may turn a multiple into banding again.
            if (snapped >= minTime && snapped <= maxTime
                    && Double.isFinite(compensated)
                    && compensated >= 1 && compensated <= maxGain) {
                time = snapped;
                gain = compensated;
            }
        }
        return new double[]{time, gain};
    }

    public static Split solve(double targetTet, int isoLow, int isoHigh,
                              long exposureLow, long exposureCap) {
        return solve(targetTet, isoLow, isoHigh, exposureLow, exposureCap, 0L);
    }

    /** The period is the light modulation period (100/120 Hz), or zero. */
    public static Split solve(double targetTet, int isoLow, int isoHigh,
                              long exposureLow, long exposureCap, long flickerPeriodNs) {
        if (isoLow <= 0 || isoHigh < isoLow || exposureLow <= 0
                || exposureCap < exposureLow) {
            throw new IllegalArgumentException("Invalid sensor exposure limits");
        }
        double[] pair = factorize(targetTet, EXP_MS, GAIN, exposureLow / 1e6,
                exposureCap / 1e6, (double) isoHigh / isoLow, flickerPeriodNs / 1e6);
        long exposure = Math.max(exposureLow, Math.min(exposureCap, Math.round(pair[0] * 1e6)));
        // Compensate only nanosecond quantization, not an unattainable target
        // above the clipped policy endpoint. Compensation may LOWER the gain.
        double quantizedGain = pair[0] * pair[1] / (exposure / 1e6);
        int iso = (int) Math.max(isoLow, Math.min(isoHigh, Math.round(quantizedGain * isoLow)));
        return new Split(exposure, iso);
    }

    public static double toTet(long exposureNs, double iso, int isoLow) {
        return (exposureNs / 1e6) * (iso / Math.max(isoLow, 1));
    }
}
