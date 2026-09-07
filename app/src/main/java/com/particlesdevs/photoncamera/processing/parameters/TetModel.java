package com.particlesdevs.photoncamera.processing.parameters;

/**
 * Splits a total exposure target (TET) into a shutter time and an overall gain
 * by walking a piecewise-linear curve of waypoints, the way GCam's
 * BuildPayloadBurstSpec does.
 *
 * <p>The waypoints are read straight out of a GCam shot dump (caiman, sensor 14,
 * "BuildPayloadBurstSpec: PSL tet model"):
 *
 * <pre>
 *   exp_time_ms     0      8      8    100    100  99999
 *   overall_gain    1      1      4     40    256    256
 * </pre>
 *
 * <p>TET is the product of the two, so the curve is monotonically increasing in
 * TET and can be inverted: given a target TET, find the segment that brackets it
 * and interpolate. The shape encodes a deliberate policy - open the shutter
 * first at base gain, then jump gain at 8 ms, then raise both together to
 * 100 ms / 40x, then spend gain alone up to 256x, and only past that extend the
 * shutter further.
 *
 * <p>Two things about this are inference rather than transcription, and are
 * flagged because they change the numbers:
 *
 * <ul>
 *   <li>The dump lists the waypoints but not the interpolation rule between
 *       them. Interpolation here is geometric (linear in log space) in both
 *       coordinates. That is the choice consistent with the waypoints
 *       themselves: on the 8 ms - 100 ms segment the shutter spans 12.5x and the
 *       gain 10x, and a geometric walk keeps the ratio between them smooth,
 *       whereas an arithmetic walk would bend it. It is not confirmed by the
 *       dump.
 *   <li>The waypoints come from a different sensor. "overall_gain" is expressed
 *       relative to that sensor's base sensitivity, so it is applied here as a
 *       multiple of this sensor's own ISO floor, and both outputs are clamped to
 *       this sensor's real limits. The 100 ms waypoints in particular sit beyond
 *       the shutter this burst is willing to use.
 * </ul>
 */
public final class TetModel {

    /** Shutter in milliseconds at each waypoint. */
    private static final double[] EXP_MS = {0.0, 8.0, 8.0, 100.0, 100.0, 99999.0};
    /** Overall gain at each waypoint, relative to the sensor's base sensitivity. */
    private static final double[] GAIN = {1.0, 1.0, 4.0, 40.0, 256.0, 256.0};

    /** A shutter/ISO pair produced by the model, already clamped to the sensor. */
    public static final class Split {
        public final long exposureNs;
        public final int iso;

        Split(long exposureNs, int iso) {
            this.exposureNs = exposureNs;
            this.iso = iso;
        }
    }

    private TetModel() {
    }

    /** TET at waypoint i, in ms*gain units. */
    private static double tetAt(int i) {
        return EXP_MS[i] * GAIN[i];
    }

    /**
     * Geometric interpolation, falling back to the endpoint when either side is
     * zero (the first waypoint has a zero shutter, which has no logarithm).
     */
    private static double geoLerp(double a, double b, double t) {
        if (a <= 0.0 || b <= 0.0) {
            return a + (b - a) * t;
        }
        return a * Math.pow(b / a, t);
    }

    /**
     * Invert the curve: find the shutter and gain whose product is the requested
     * TET.
     *
     * @param targetTet   target total exposure, in ms*gain units
     * @param isoLow      the sensor's base sensitivity, i.e. gain 1.0
     * @param isoHigh     the sensor's maximum sensitivity
     * @param exposureLow the sensor's minimum shutter, in nanoseconds
     * @param exposureCap the largest shutter this burst may use, in nanoseconds;
     *                    this is the caller's cap, not necessarily the sensor's
     * @return the split, clamped to the sensor's limits
     */
    public static Split solve(double targetTet, int isoLow, int isoHigh,
                              long exposureLow, long exposureCap) {
        double expMs;
        double gain;

        if (targetTet <= tetAt(0)) {
            expMs = EXP_MS[0];
            gain = GAIN[0];
        } else if (targetTet >= tetAt(EXP_MS.length - 1)) {
            expMs = EXP_MS[EXP_MS.length - 1];
            gain = GAIN[GAIN.length - 1];
        } else {
            int seg = EXP_MS.length - 2;
            for (int i = 0; i < EXP_MS.length - 1; i++) {
                if (targetTet >= tetAt(i) && targetTet <= tetAt(i + 1)) {
                    seg = i;
                    break;
                }
            }
            double lo = tetAt(seg);
            double hi = tetAt(seg + 1);
            // Position within the segment, in log-TET so the walk matches the
            // geometric interpolation used for the coordinates themselves.
            double t;
            if (hi <= lo) {
                t = 0.0;
            } else if (lo <= 0.0) {
                t = targetTet / hi;
            } else {
                t = Math.log(targetTet / lo) / Math.log(hi / lo);
            }
            t = Math.max(0.0, Math.min(1.0, t));
            expMs = geoLerp(EXP_MS[seg], EXP_MS[seg + 1], t);
            gain = geoLerp(GAIN[seg], GAIN[seg + 1], t);
        }

        long exposureNs = Math.round(expMs * 1e6);
        exposureNs = Math.max(exposureLow, Math.min(exposureCap, exposureNs));

        // Whatever the shutter clamp took away is returned as gain, so the
        // product stays on target instead of the frame silently coming out dark.
        double remainingGain = targetTet / Math.max(exposureNs / 1e6, 1e-9);
        gain = Math.max(gain, remainingGain);

        long iso = Math.round(gain * isoLow);
        iso = Math.max(isoLow, Math.min(isoHigh, iso));

        return new Split(exposureNs, (int) iso);
    }

    /**
     * Convert a shutter/ISO product into the model's TET units.
     *
     * @param exposureNs shutter in nanoseconds
     * @param iso        sensitivity
     * @param isoLow     the sensor's base sensitivity, i.e. gain 1.0
     */
    public static double toTet(long exposureNs, double iso, int isoLow) {
        return (exposureNs / 1e6) * (iso / Math.max(isoLow, 1));
    }
}
